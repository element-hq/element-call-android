/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.test.audio

import io.element.android.call.api.audio.CallAudioDevice
import io.element.android.call.api.audio.CallAudioDeviceController
import io.element.android.call.api.audio.CallAudioDeviceType
import kotlinx.coroutines.flow.MutableStateFlow

class FakeCallAudioDeviceController(
    initialDevices: List<CallAudioDevice> = listOf(anEarpiece(), aSpeaker()),
) : CallAudioDeviceController {
    override val devices = MutableStateFlow(initialDevices)
    override val selectedDevice = MutableStateFlow<CallAudioDevice?>(null)

    var startCount = 0
    var stopCount = 0

    /** Every route asked for, in order, so a test can tell "never applied" from "applied twice". */
    val selections = mutableListOf<CallAudioDevice>()

    /** What the last [start] asked for, or null before any. */
    var lastPreferLoudspeaker: Boolean? = null
        private set

    override fun start(preferLoudspeaker: Boolean) {
        startCount++
        lastPreferLoudspeaker = preferLoudspeaker
        // Same rule as the real one, on a list the test has already put in headset-first order:
        // a headset wins regardless, and only the two built-in outputs swap places for video.
        val best = if (preferLoudspeaker) {
            devices.value.firstOrNull { it.type != CallAudioDeviceType.EARPIECE } ?: devices.value.firstOrNull()
        } else {
            devices.value.firstOrNull()
        }
        best?.let(::select)
    }

    override fun select(device: CallAudioDevice) {
        selections += device
        selectedDevice.value = device
    }

    override fun stop() {
        stopCount++
        selectedDevice.value = null
        proximityBlankingAllowed = false
    }

    /**
     * Whether the caller currently says the phone might be at an ear.
     *
     * Worth asserting on rather than ignoring: held wrongly, the proximity lock blanks the screen
     * under the user's finger, and the failure only shows up on a device with the phone in hand.
     */
    var proximityBlankingAllowed = false
        private set

    /** Every value it has been set to, in order, so a test can see it turn off as well as on. */
    val proximityBlankingChanges = mutableListOf<Boolean>()

    override fun setProximityBlankingAllowed(allowed: Boolean) {
        if (proximityBlankingAllowed == allowed) return
        proximityBlankingAllowed = allowed
        proximityBlankingChanges += allowed
    }
}

fun anEarpiece() = CallAudioDevice(id = 1, type = CallAudioDeviceType.EARPIECE, productName = null)

fun aSpeaker() = CallAudioDevice(id = 2, type = CallAudioDeviceType.SPEAKER, productName = null)

fun aBluetoothHeadset(name: String = "WH-1000XM4") = CallAudioDevice(
    id = 3,
    type = CallAudioDeviceType.BLUETOOTH,
    productName = name,
)
