/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.call.api.audio.CallAudioDevice
import io.element.android.call.api.audio.CallAudioDeviceController
import io.element.android.call.api.audio.CallAudioDeviceType
import io.element.android.libraries.di.annotations.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.Executors

/**
 * Real audio routing over `AudioManager`.
 *
 * The mechanics here are ported from `WebViewAudioManager`, which is the version proven on real
 * devices - the device-type priority, the hot-plug callback, the re-assert listener and the
 * proximity wake lock all come from there. Two of its compromises are deliberately *not* carried
 * over, because both exist to work around the WebView rather than the platform:
 *
 * - **Bluetooth below Android 12.** That path disables Bluetooth entirely under S and shows the user
 *   an error, because - in its own words - "the WebView approach breaks when using the legacy
 *   Bluetooth audio APIs". There is no WebView here, so the legacy SCO calls are used as intended
 *   and Bluetooth works on every supported version.
 * - **Selection living somewhere else.** There, Element Call owns the picker and Android only
 *   enumerates and applies, which is why it needs a listener that fights to re-assert the route
 *   whenever something changes it. This owns both halves, so the listener only has to notice.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultCallAudioDeviceController(
    @ApplicationContext private val context: Context,
) : CallAudioDeviceController {
    private val audioManager = requireNotNull(context.getSystemService<AudioManager>())

    private val _devices = MutableStateFlow<List<CallAudioDevice>>(emptyList())
    override val devices: StateFlow<List<CallAudioDevice>> = _devices.asStateFlow()

    private val _selectedDevice = MutableStateFlow<CallAudioDevice?>(null)
    override val selectedDevice: StateFlow<CallAudioDevice?> = _selectedDevice.asStateFlow()

    /** The mode before we touched it. Non-null exactly while we are the ones holding the device. */
    private var modeBeforeCall: Int? = null

    /** Whether the loudspeaker outranks the earpiece for this call: see [start]. */
    private var preferLoudspeaker = false

    private var hasRegisteredCallbacks = false

    /**
     * Turns the screen off when the phone is against an ear, and only then.
     *
     * Held exactly while the earpiece is the route: on a speakerphone or headset call the phone is
     * being looked at, and blanking it because something passed the sensor would be a bug.
     */
    private val proximityWakeLock by lazy {
        context.getSystemService<PowerManager>()
            ?.takeIf { it.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) }
            ?.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "${context.packageName}:CallProximityWakeLock")
    }

    /**
     * Notices when something else moves the route out from under us.
     *
     * Only reports it. The WebView path re-asserts its own choice here, because the selection lives
     * in a web app that would otherwise disagree with the platform; here the platform's answer is
     * the truth and the UI follows it.
     */
    @get:RequiresApi(Build.VERSION_CODES.S)
    private val communicationDeviceChangedListener by lazy {
        AudioManager.OnCommunicationDeviceChangedListener { device ->
            Timber.d("CallAudio: platform routed to ${device?.type}")
            _selectedDevice.value = device?.toCallAudioDevice()
        }
    }

    /**
     * Keeps the list live while the call runs.
     *
     * Plugging in a headset mid-call is the ordinary case, not an edge one, and a picker that only
     * showed what was connected when the call started is the thing users report as broken.
     */
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshDevices()
            // A headset appearing mid-call is a request to use it: it is the reason someone reached
            // for it. Only ever an upgrade though - it must not drag a deliberate speakerphone call
            // back to the earpiece just because the earpiece "appeared".
            val best = _devices.value.firstOrNull() ?: return
            val current = _selectedDevice.value
            if (current == null || priorityOf(best.type) < priorityOf(current.type)) {
                select(best)
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshDevices()
            // If what we were using has gone, fall to the next best rather than leaving the call
            // routed at nothing.
            val current = _selectedDevice.value
            if (current != null && _devices.value.none { it.id == current.id }) {
                _devices.value.firstOrNull()?.let(::select)
            }
        }
    }

    override fun start(preferLoudspeaker: Boolean) {
        // Re-entrant on purpose: losing the original mode to a second start would mean handing back
        // MODE_IN_COMMUNICATION for good.
        if (modeBeforeCall != null) return
        modeBeforeCall = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        // Set before the first refresh, since it decides the order the devices come out in.
        this.preferLoudspeaker = preferLoudspeaker
        refreshDevices()
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.addOnCommunicationDeviceChangedListener(Executors.newSingleThreadExecutor(), communicationDeviceChangedListener)
        }
        hasRegisteredCallbacks = true

        // Chosen rather than assumed. The platform's idea of a default is not reliably the earpiece,
        // and a voice call that starts on the loudspeaker is startling in a way the reverse is not.
        // The list is already in this call's order, so its head is the answer for either kind.
        _devices.value.firstOrNull()?.let(::select)
    }

    override fun select(device: CallAudioDevice) {
        Timber.i("CallAudio: routing to ${device.type}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val target = audioManager.availableCommunicationDevices.firstOrNull { it.id == device.id }
            if (target == null) {
                Timber.w("CallAudio: device ${device.id} is no longer available")
                return
            }
            if (!audioManager.setCommunicationDevice(target)) {
                Timber.w("CallAudio: the platform refused to route to ${device.type}")
                return
            }
        } else {
            @Suppress("DEPRECATION")
            when (device.type) {
                CallAudioDeviceType.BLUETOOTH -> {
                    // Works here precisely because there is no WebView in the path. SCO is a link
                    // that has to be brought up before it carries anything, unlike every other
                    // route, which is why it is the one type with a call of its own.
                    audioManager.isSpeakerphoneOn = false
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                }
                else -> {
                    if (audioManager.isBluetoothScoOn) {
                        audioManager.stopBluetoothSco()
                        audioManager.isBluetoothScoOn = false
                    }
                    audioManager.isSpeakerphoneOn = device.type == CallAudioDeviceType.SPEAKER
                }
            }
        }
        _selectedDevice.value = device
        updateProximityWakeLock(device)
    }

    /** False until the caller says otherwise: see [setProximityBlankingAllowed]. */
    private var isProximityBlankingAllowed = false

    override fun stop() {
        if (hasRegisteredCallbacks) {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.removeOnCommunicationDeviceChangedListener(communicationDeviceChangedListener)
            }
            hasRegisteredCallbacks = false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            if (audioManager.isBluetoothScoOn) {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }

        @Suppress("WakeLock")
        if (proximityWakeLock?.isHeld == true) proximityWakeLock?.release()
        // Reset, so a next call starts from "not at an ear" rather than inheriting the last one's
        // answer before anybody has looked at the new call.
        isProximityBlankingAllowed = false

        // Only restore a mode we actually saved: writing MODE_NORMAL blind would clobber whatever
        // another call in this process had set up.
        modeBeforeCall?.let { audioManager.mode = it }
        modeBeforeCall = null
        preferLoudspeaker = false
        _selectedDevice.value = null
        _devices.value = emptyList()
    }

    private fun refreshDevices() {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices
        } else {
            @Suppress("DEPRECATION")
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }
        }
        _devices.value = raw
            .mapNotNull { it.toCallAudioDevice() }
            .distinctBy { it.id }
            .sortedBy { priorityOf(it.type) }
    }

    /**
     * How likely a device is to be the one wanted for this call, lowest first.
     *
     * Anything the user actively plugged in or paired comes before either built-in output, because
     * reaching for a headset is itself the instruction. Between the two built-in ones the call decides:
     * the earpiece for a voice call held to a head, the loudspeaker for a video call laid on a desk
     * or held at arm's length - see [start]. `WebViewAudioManager` ranks the loudspeaker first
     * unconditionally, which is right for a meeting and wrong for a phone call.
     */
    private fun priorityOf(type: CallAudioDeviceType): Int = when (type) {
        CallAudioDeviceType.BLUETOOTH -> 0
        CallAudioDeviceType.USB_HEADSET -> 1
        CallAudioDeviceType.USB_DEVICE -> 2
        CallAudioDeviceType.USB_ACCESSORY -> 3
        CallAudioDeviceType.WIRED_HEADSET -> 4
        CallAudioDeviceType.WIRED_HEADPHONES -> 5
        CallAudioDeviceType.EARPIECE -> if (preferLoudspeaker) 7 else 6
        CallAudioDeviceType.SPEAKER -> if (preferLoudspeaker) 6 else 7
    }

    override fun setProximityBlankingAllowed(allowed: Boolean) {
        if (isProximityBlankingAllowed == allowed) return
        isProximityBlankingAllowed = allowed
        updateProximityWakeLock(_selectedDevice.value)
    }

    /**
     * Hold the proximity lock only when the phone might actually be at someone's ear.
     *
     * Both halves are required. The earpiece being the output is the obvious one; the caller's
     * judgement is the one this used to be missing, and its absence made the lock fire whenever a
     * hand went near the top of the screen for the whole length of a call - including while the call
     * was minimized and the user was reading something else entirely, which then made the call
     * unreachable: opening the notification shade means reaching for the top of the screen, and
     * reaching for the top of the screen turned the display off.
     */
    private fun updateProximityWakeLock(device: CallAudioDevice?) {
        val shouldHold = isProximityBlankingAllowed && device?.type == CallAudioDeviceType.EARPIECE
        @Suppress("WakeLock", "WakeLockTimeout")
        if (shouldHold) {
            if (proximityWakeLock?.isHeld == false) proximityWakeLock?.acquire()
        } else if (proximityWakeLock?.isHeld == true) {
            proximityWakeLock?.release()
        }
    }
}

private fun AudioDeviceInfo.toCallAudioDevice(): CallAudioDevice? {
    val deviceType = type.toCallAudioDeviceType() ?: return null
    return CallAudioDevice(
        id = id,
        type = deviceType,
        productName = productName?.toString()?.takeIf { it.isNotBlank() },
    )
}

/** Null for anything a call cannot sensibly come out of, which is what filters the raw device list. */
private fun Int.toCallAudioDeviceType(): CallAudioDeviceType? = when (this) {
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> CallAudioDeviceType.BLUETOOTH
    AudioDeviceInfo.TYPE_USB_HEADSET -> CallAudioDeviceType.USB_HEADSET
    AudioDeviceInfo.TYPE_USB_DEVICE -> CallAudioDeviceType.USB_DEVICE
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> CallAudioDeviceType.USB_ACCESSORY
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> CallAudioDeviceType.WIRED_HEADSET
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> CallAudioDeviceType.WIRED_HEADPHONES
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> CallAudioDeviceType.EARPIECE
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> CallAudioDeviceType.SPEAKER
    else -> null
}
