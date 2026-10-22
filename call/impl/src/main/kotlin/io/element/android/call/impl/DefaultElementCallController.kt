/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl

import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallController
import io.element.android.call.api.ElementCallData
import io.element.android.call.api.ElementCallEndReason
import io.element.android.call.api.ElementCallFailure
import io.element.android.call.api.ElementCallHandRequestFailure
import io.element.android.call.api.ElementCallLifecycleListener
import io.element.android.call.api.ElementCallOptions
import io.element.android.call.api.ElementCallRoomContextProvider
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.ElementCallWindowRect
import io.element.android.call.api.audio.AudioFocus
import io.element.android.call.api.audio.CallAudioDevice
import io.element.android.call.api.audio.CallAudioDeviceController
import io.element.android.call.api.rtc.MatrixRtcCall
import io.element.android.call.api.rtc.MatrixRtcCallEnd
import io.element.android.call.api.rtc.MatrixRtcCallEvent
import io.element.android.call.api.rtc.MatrixRtcCallIntent
import io.element.android.call.api.rtc.MatrixRtcCallKind
import io.element.android.call.api.rtc.MatrixRtcCallStatus
import io.element.android.call.api.rtc.MatrixRtcClient
import io.element.android.call.api.rtc.MatrixRtcDetailWindow
import io.element.android.call.api.rtc.MatrixRtcGroupNotify
import io.element.android.call.api.rtc.MatrixRtcJoinException
import io.element.android.call.api.rtc.MatrixRtcLeaveReason
import io.element.android.call.api.rtc.MatrixRtcMediaSession
import io.element.android.call.api.rtc.MatrixRtcMembershipFormat
import io.element.android.call.api.rtc.MatrixRtcRing
import io.element.android.call.api.rtc.MatrixRtcRingPhase
import io.element.android.call.api.rtc.MatrixRtcRoom
import io.element.android.call.api.rtc.MatrixRtcScreenCaptureToken
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.MatrixRtcVideoConstraints
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import io.element.android.call.impl.audio.CallSoundEffect
import io.element.android.call.impl.audio.CallSoundEffectRenderer
import io.element.android.call.impl.audio.JoinLeaveSoundPolicy
import io.element.android.call.impl.audio.RaisedHandSoundPolicy
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * Owns the one native call that can be running, for as long as it runs.
 *
 * This exists because a call is not a screen. The spike first drove everything from a presenter
 * inside an Activity, which made the Activity's composition the call's lifetime: navigating away
 * ended the call, so the only shape the UI could take was a full-screen one in its own task - the
 * very limitation that makes the Element Call WebView awkward to embed. Holding the session here, on
 * the session's [scope], means the call is a fact about the session rather than about whatever is on
 * screen, and the full-screen UI and the minimized bar become two renderings of it.
 *
 * Single call by construction: [startCall] refuses while one is running. Multi-call would need a map
 * keyed by room, and nothing in the product asks for it yet.
 *
 * Everything that mutates goes through [mutex] on [scope], so callers can fire and forget from any
 * thread and the UI only ever sees whole snapshots.
 */
internal class DefaultElementCallController(
    private val scope: CoroutineScope,
    private val platform: ElementCallPlatform,
    private val rtcClient: MatrixRtcClient,
    private val audioDeviceController: CallAudioDeviceController,
    private val audioFocus: AudioFocus,
    private val lifecycleListener: ElementCallLifecycleListener,
    private val roomContextProvider: ElementCallRoomContextProvider,
    private val options: ElementCallOptions,
) : ElementCallController {
    private val mutex = Mutex()

    private val _state = MutableStateFlow<ElementCallSnapshot?>(null)

    override val state: StateFlow<ElementCallSnapshot?> = _state.asStateFlow()

    /**
     * A maximized call, or a minimized one with a picture in it. The second case completes the
     * handover the floating tile design implies: a minimized video call is already a tile floating
     * over our own app, so leaving the app should hand that tile to the system rather than making it
     * vanish. A minimized *audio* call is deliberately excluded - a floating window showing an avatar
     * over someone's launcher earns nothing, and the ongoing-call notification is the right
     * affordance for it.
     */
    override val shouldEnterPictureInPicture: StateFlow<Boolean> = _state
        .map { it != null && it.isMaximized }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _isInPictureInPicture = MutableStateFlow(false)

    override val isInPictureInPicture: StateFlow<Boolean> = _isInPictureInPicture.asStateFlow()

    private val _pictureInPictureSource = MutableStateFlow<ElementCallWindowRect?>(null)

    override val pictureInPictureSource: StateFlow<ElementCallWindowRect?> = _pictureInPictureSource.asStateFlow()

    private val _pictureInPictureEntry = MutableStateFlow<ElementCallWindowRect?>(null)

    override val pictureInPictureEntry: StateFlow<ElementCallWindowRect?> = _pictureInPictureEntry.asStateFlow()

    init {
        // Keeps the audio layer told whether the phone might be at somebody's ear.
        //
        // The proximity sensor blanks the screen when it is covered, and it sits at the top of the
        // phone beside the earpiece - so held indiscriminately it turns the display off whenever a
        // hand reaches for anything near the top of the screen. That is a nuisance during a call you
        // are looking at and genuinely trapping when the call is minimized: reaching for the
        // notification shade is reaching for the top of the screen, so the one gesture that could get
        // back to the call was the gesture that blanked it.
        //
        // Three conditions, all necessary. **Maximized**, because a minimized call means the user is
        // reading something else. **No video**, because a picture is something you look at rather
        // than hold to your face - and that covers a screen share too. **Foreground**, because
        // blanking another app's screen is never ours to do. The earpiece condition is the audio
        // layer's own and is checked there.
        //
        // In init rather than per call: this lives as long as the session, and a null call resolves
        // to false, which is the right answer between calls.
        scope.launch {
            combine(
                _state,
                _isInPictureInPicture,
                lifecycleListener.isAppInForeground,
            ) { call, isInPip, isForeground ->
                call != null && call.isMaximized && !call.hasVideo && !isInPip && isForeground
            }
                .distinctUntilChanged()
                .collect { audioDeviceController.setProximityBlankingAllowed(it) }
        }
    }

    override fun setInPictureInPicture(isInPictureInPicture: Boolean) {
        _isInPictureInPicture.value = isInPictureInPicture
        // Coming back out of PiP has to leave the call maximized rather than merely visible: PiP was
        // entered *from* the full-screen call, and dropping the user into a minimized bar over the
        // room list would be a different screen than the one they left.
        if (!isInPictureInPicture && _state.value != null) {
            setMaximized(true)
        }
    }

    override fun setPictureInPictureSource(source: ElementCallWindowRect?) {
        // In the window nothing draws the tile, so the screen going away reports null; taking it
        // would let the next params update reset the window to the system's default shape.
        if (_isInPictureInPicture.value) return
        _pictureInPictureSource.value = source
    }

    override fun setPictureInPictureEntry(entry: ElementCallWindowRect?) {
        _pictureInPictureEntry.value = entry
    }

    /**
     * Edit the running call's snapshot, or do nothing if there is no call.
     *
     * Every collector in [startObservers] outlives the moment the call is torn down by a hair -
     * cancellation is not instantaneous - so "no call" is a normal thing for a late emission to
     * find, not an error.
     */
    private fun updateState(block: (ElementCallSnapshot) -> ElementCallSnapshot) {
        _state.update { it?.let(block) }
    }

    /** The join-and-observe coroutine for the running call. Cancelled as the first step of teardown. */
    private var callJob: Job? = null

    /** Separate from [callJob] because the room is wanted before the microphone is granted. */
    private var roomJob: Job? = null

    /**
     * One shared video flow per member and stream kind, so however many tiles draw something, one
     * stream is open. Keyed by member id, which is minted fresh per join, so entries cannot outlive
     * their meaning - and by kind, because a member sharing their screen while their camera is on has
     * two independent streams that would otherwise collide on one key.
     */
    private val sharedVideoFlows = ConcurrentHashMap<VideoStreamKey, Flow<MatrixRtcVideoFrame>>()

    private data class VideoStreamKey(val memberId: String, val kind: MatrixRtcStreamKind)

    /** Hosts the sharing above. Tied to the media connection, since that is what the flows read. */
    private var videoSharingScope: CoroutineScope? = null

    private var rtcRoom: MatrixRtcRoom? = null
    private var rtcCall: MatrixRtcCall? = null
    private var call: MatrixRtcMediaSession? = null
    private var joinLeaveSoundPolicy: JoinLeaveSoundPolicy? = null
    private var raisedHandSoundPolicy: RaisedHandSoundPolicy? = null
    private var soundEffectRenderer: CallSoundEffectRenderer? = null
    private var composedTiles: Set<MatrixRtcTileId> = emptySet()
    private var detailWindow: MatrixRtcDetailWindow? = null

    /** Not cleared with the call: it is the layout's, set once for the controller. */
    private var rankingThreshold: Int? = null

    /**
     * Begin a call. Does nothing if one is already running, including for the same room: rejoining
     * would mint a new member id and leave the old membership behind as a ghost.
     *
     * Returns immediately. Nothing happens beyond entering [ElementCallConnection.RequestingPermission]
     * until the host answers with [setMicrophonePermissionGranted] - only an Activity can ask for a
     * permission, and this is not one.
     */
    override fun startCall(callData: ElementCallData) {
        scope.launch {
            mutex.withLock {
                if (_state.value != null) {
                    Timber.w("ElementCall: a call is already running, ignoring start for ${callData.roomId}")
                    return@withLock
                }
                Timber.i("ElementCall: call requested for ${callData.roomId}")
                _state.value = ElementCallSnapshot(
                    callData = callData,
                    connection = ElementCallConnection.RequestingPermission,
                    isScreenShareAvailable = options.isScreenSharingEnabled,
                    // What the call will join with, not what is capturing - nothing is, yet. The
                    // control bar is on screen from this moment, so a video call whose button read
                    // "off" until the media connected would announce the wrong call and then flip
                    // under the user's finger. From here on this is the camera's desired state, and
                    // the tap that changes it is the answer runCall joins on.
                    isCameraEnabled = !callData.isAudioCall,
                    isCameraSwitchAvailable = platform.isCameraSwitchAvailable(),
                )
                // Reported as soon as the call is requested rather than once it connects, so a host's
                // Join button hides while we are still joining rather than blinking through an
                // interval where the app believes we are not in the call we are joining.
                lifecycleListener.onCallStarted(callData)
                // Started here rather than after joining, because the bar is on screen from this
                // moment and a call labelled with a room id would be worse than one labelled late.
                roomJob = scope.launch { observeRoom(callData) }
            }
        }
    }

    /**
     * What kind of call this join is (feature-hq 030 C1): a DM call, which rings only when the user chose to
     * call, or a group call, which only ever shows the others a notification, and only when we start it.
     */
    private suspend fun callKindFor(callData: ElementCallData): MatrixRtcCallKind {
        // Defaults to a group call rather than a ring if the room cannot be read in time: an unwanted
        // ring wakes people up, a missing one only makes the call quieter than it should have been.
        val isDm = withTimeoutOrNull(ROOM_CONTEXT_TIMEOUT_MS) {
            roomContextProvider.roomContext(callData.roomId).first()
        }?.isDm == true
        val intent = if (callData.isAudioCall) MatrixRtcCallIntent.AUDIO else MatrixRtcCallIntent.VIDEO
        return when {
            isDm && callData.isStartingCall -> MatrixRtcCallKind.Direct(MatrixRtcRing(intent, options.ringLifetime?.inWholeMilliseconds?.toULong()))
            isDm -> MatrixRtcCallKind.Direct(ring = null)
            callData.isStartingCall -> MatrixRtcCallKind.Group(MatrixRtcGroupNotify(intent))
            else -> MatrixRtcCallKind.Group(notify = null)
        }
    }

    /**
     * Watch the room the call is in, for the two things the call needs from it: what to call the
     * call, and who its participants are.
     *
     * The RTC layer knows participants only as member ids and user ids - it has no idea what anyone
     * is called or what they look like, and no reason to. Names and avatars come from the host's room
     * context, and joining the two up is this class's job rather than the UI's, so that the bar and
     * the call screen do not each have to do it.
     */
    private suspend fun observeRoom(callData: ElementCallData) {
        roomContextProvider.roomContext(callData.roomId).collect { context ->
            updateState {
                it.copy(
                    roomName = context.displayName,
                    isDm = context.isDm,
                    roomMembers = context.members.toImmutableMap(),
                )
            }
        }
    }

    /**
     * Answer the microphone permission request. Denial fails the call: it is audio first, and
     * `AudioRecord` would fail anyway.
     */
    override fun setMicrophonePermissionGranted(granted: Boolean) {
        scope.launch {
            mutex.withLock {
                val current = _state.value ?: return@withLock
                if (!granted) {
                    Timber.i("ElementCall: microphone permission denied")
                    updateState { it.copy(connection = ElementCallConnection.Failed(ElementCallFailure.Other("Microphone permission denied"))) }
                    return@withLock
                }
                // The host re-reports a permission it already has whenever it is recreated, so this
                // has to be idempotent or a configuration change would join the call a second time.
                if (current.isMicrophonePermissionGranted) return@withLock
                updateState { it.copy(isMicrophonePermissionGranted = true) }

                // Both must be in place before capture starts: the service so the microphone survives
                // backgrounding, the audio focus so other apps duck.
                platform.startForegroundService()
                audioFocus.requestAudioFocus {}

                callJob = scope.launch { runCall(current.callData) }
            }
        }
    }

    override fun setCameraPermissionGranted(granted: Boolean) {
        scope.launch {
            updateState { it.copy(isCameraPermissionGranted = granted) }
            if (!granted) return@launch
            // Started again, not for the first time: the service is already running for the
            // microphone, and this is what lets it add the camera type it could not claim before.
            // Without it, capture stops the moment the call is backgrounded.
            platform.startForegroundService()
            // Turned on as soon as it is granted: once a call is running, the only way to reach this
            // is having just tapped the camera button, and making the user tap it again would be an
            // odd reward for saying yes.
            //
            // Only once one is running, though. Before that a grant is not an ask - a host may answer
            // this up front, as the screen does for a video call, and on an audio call that would
            // start a camera nobody reached for - so what the call joins with is recorded by the
            // button, in setCameraEnabled, and nowhere else.
            call?.setCameraEnabled(true)
        }
    }

    override fun setMicrophoneMuted(muted: Boolean) {
        // Muting reaches the transport as well as capture, so it suspends.
        scope.launch {
            // Recorded on the snapshot whether or not there is a call yet: before there is one this
            // is the only place the intent lives, and runCall publishes the microphone in that state
            // rather than unmuting the user who muted themselves while it connected. Once the call is
            // up the observer hands the same value straight back.
            updateState { it.copy(isMicrophoneMuted = muted) }
            call?.setMicrophoneMuted(muted)
        }
    }

    override fun setHandRaised(raised: Boolean) {
        val rtcCall = rtcCall ?: return
        updateState { it.copy(handRequestFailure = null) }
        // Not gated on a request in flight: the core holds the hand as a desired state, so a second tap is the
        // reversal, and the first then resolves quietly (034 C12). It fails only on a refusal.
        scope.launch {
            val result = if (raised) rtcCall.raiseHand() else rtcCall.lowerHand()
            Timber.i("ElementCall: ${if (raised) "raising" else "lowering"} our hand ${if (result.isSuccess) "done" else "refused"}")
            if (result.isFailure) {
                updateState { it.copy(handRequestFailure = if (raised) ElementCallHandRequestFailure.RAISE else ElementCallHandRequestFailure.LOWER) }
            }
        }
    }

    override fun clearHandRequestFailure() {
        updateState { it.copy(handRequestFailure = null) }
    }

    /**
     * Turn the camera on or off, or record what the call should join with when there is no call yet.
     *
     * The two are the same field: before the media connects the snapshot *is* the camera's state, and
     * runCall reads it back. Once there is a call, its own flow takes over and keeps the button
     * honest - a camera that refuses to start puts it back off.
     */
    override fun setCameraEnabled(enabled: Boolean) {
        scope.launch {
            val call = call
            if (call == null) {
                updateState { it.copy(isCameraEnabled = enabled) }
            } else {
                call.setCameraEnabled(enabled)
            }
        }
    }

    override fun switchCamera() {
        scope.launch { call?.switchCamera() }
    }

    override fun setAudioTestToneEnabled(enabled: Boolean) {
        call?.setAudioTestToneEnabled(enabled)
    }

    override fun selectAudioDevice(device: CallAudioDevice) {
        audioDeviceController.select(device)
    }

    override fun setMaximized(maximized: Boolean) {
        updateState { it.copy(isMaximized = maximized) }
    }

    override fun toggleTileStats() {
        updateState { it.copy(isTileStatsVisible = !it.isTileStatsVisible) }
    }

    /**
     * Shared rather than handed straight through, because [MatrixRtcMediaSession.videoFrames] is **cold and
     * one stream per collector** - its own KDoc says "collect it once per member; two collectors
     * means two streams". Two tiles on one member opened two `videoStream` handles on the same track,
     * and the second one closing under the first crashed the core inside `VideoSinkWrapper::on_frame`.
     * That contract cannot be left to whoever writes the layout.
     *
     * By [SharedFrameStream] rather than `shareIn`, because frames are now reference-counted native
     * memory and `shareIn` cannot carry them: its `emit` resumes once a subscriber has been *woken*,
     * not once that subscriber's collector has run, so releasing after `emit` frees the frame while
     * the renderer is still being dispatched to. See that class for the full accounting.
     *
     * The property that made the flow cold in the first place is kept: no tile drawing a member means
     * no subscriber, which means the upstream is cancelled and the core stops decoding for nobody.
     * Minimizing the call still stops video; it just no longer matters how many tiles were drawing it.
     */
    override fun videoFrames(memberId: String, kind: MatrixRtcStreamKind): Flow<MatrixRtcVideoFrame> {
        val currentCall = call ?: return emptyFlow()
        val scope = videoSharingScope ?: return emptyFlow()
        return sharedVideoFlows.computeIfAbsent(VideoStreamKey(memberId, kind)) {
            SharedFrameStream(
                upstream = currentCall.videoFrames(memberId, kind),
                scope = scope,
                lingerMillis = VIDEO_STREAM_LINGER_MS,
            ).frames
        }
    }

    /**
     * The order here is the whole reason this lives in the controller rather than being passed
     * straight through: from Android 14 the foreground service has to *already* be running with the
     * `mediaProjection` type when the projection is claimed, and claiming happens inside
     * `setScreenShareEnabled`. Upgrading the service afterwards, or in parallel, is a
     * `SecurityException` from the platform rather than a warning.
     *
     * Refused outright when the host has not turned screen sharing on: the screen offers no button
     * then, but the controller works with no UI attached, and a host that reaches this without the
     * manifest half would hit that same `SecurityException`. Stopping is always allowed.
     */
    override fun setScreenShareEnabled(token: MatrixRtcScreenCaptureToken?) {
        scope.launch {
            val currentCall = call ?: return@launch
            if (token != null) {
                if (!options.isScreenSharingEnabled) {
                    Timber.w("ElementCall: screen sharing is not enabled in the options, ignoring the share request")
                    return@launch
                }
                platform.startForegroundService(isProjecting = true)
                currentCall.setScreenShareEnabled(enabled = true, token = token)
            } else {
                currentCall.setScreenShareEnabled(enabled = false)
                // Back to microphone and camera only, so the system stops saying the screen is being
                // recorded the moment it stops being recorded.
                platform.startForegroundService(isProjecting = false)
            }
        }
    }

    /**
     * Driven from the tile that draws it, because the layout is the only thing that knows. Without
     * this every tile receives the sender's best simulcast layer whatever size it is shown at - a
     * 100dp thumbnail decoding and compositing 720p, which is most of what the phone was doing.
     */
    override fun setVideoConstraints(memberId: String, kind: MatrixRtcStreamKind, constraints: MatrixRtcVideoConstraints) {
        scope.launch { call?.setVideoConstraints(memberId, kind, constraints) }
    }

    override fun setComposedTiles(tileIds: Set<MatrixRtcTileId>) {
        // Kept, not only forwarded: the screen usually declares before media connects, and a call
        // that started with nothing declared would poll nothing until the next page turn.
        composedTiles = tileIds
        call?.setComposedTiles(tileIds)
    }

    override fun setDetailWindow(window: MatrixRtcDetailWindow) {
        // Same reason as the composed set: the stage is usually mounted before the media is, and the
        // core's default window is the whole call.
        detailWindow = window
        call?.setDetailWindow(window)
    }

    override fun setRankingThreshold(tiles: Int) {
        rankingThreshold = tiles
        call?.setRankingThreshold(tiles)
    }

    override fun hangUp() {
        scope.launch { endCall(leave = true) }
    }

    private suspend fun runCall(callData: ElementCallData) {
        updateState { it.copy(connection = ElementCallConnection.Joining) }
        Timber.i("ElementCall: joining ${callData.roomId}")

        // Read once, here, rather than observed: the format is fixed for the lifetime of a room -
        // it decides the member id, the SFU identity and the token endpoint as well as the wire
        // format - so changing it mid-call is not something the core could act on.
        //
        // Temporary: pinned to the room-state format until the sticky formats, whose events now go through the
        // SDK, have been tried against other clients - see `docs/FEEDBACK.md`, "SDK backend". The
        // option is still read so the pin is visible in the log, and so that lifting it is a one-line change here.
        val preferredMembershipFormat = options.membershipFormat
        val membershipFormat = MatrixRtcMembershipFormat.ROOM_STATE
        if (preferredMembershipFormat != membershipFormat) {
            Timber.i("ElementCall: membership format $preferredMembershipFormat is not available on the released SDK, pinned to $membershipFormat")
        }
        Timber.i("ElementCall: joining with membership format $membershipFormat")

        val room = rtcClient.room(callData.roomId, membershipFormat).getOrElse {
            fail("Join failed: ${it.message}")
            return
        }
        rtcRoom = room
        val kind = callKindFor(callData)
        // A DM call we start shows that it is calling from here, before the join returns (030 C3).
        if (kind is MatrixRtcCallKind.Direct && kind.ring != null) {
            updateState { it.copy(connection = ElementCallConnection.Calling(callee = null, isRinging = false)) }
        }
        val joined = room.joinCall(kind = kind).getOrElse {
            when (it) {
                is MatrixRtcJoinException.TimedOut -> fail(ElementCallFailure.JoinTimedOut, "Join failed: ${it.message}")
                is MatrixRtcJoinException.Refused -> fail(ElementCallFailure.JoinRefused, "Join failed: ${it.message}")
                else -> fail("Join failed: ${it.message}")
            }
            return
        }
        rtcCall = joined
        // Whatever was ringing for this call has now been answered. Until this lands the host's
        // ringtone keeps playing over the call, its incoming UI stays up, and its missed-call timeout
        // still fires - so answering looks like it failed while actually having worked. A no-op for an
        // outgoing call, which was never ringing.
        lifecycleListener.onCallJoined(callData)

        // coroutineScope holds until the observers are cancelled, which is what teardown does.
        // The call's sounds and its route from the join, not from media: a DM rings before media is up, and its
        // ringback plays through the call's audio (030 R24), on whatever output the picker shows.
        joinLeaveSoundPolicy = JoinLeaveSoundPolicy(options.participantCapForJoinLeaveSounds)
        raisedHandSoundPolicy = RaisedHandSoundPolicy()
        soundEffectRenderer = platform.createCallSoundEffectRenderer()
        // Communication mode is device-wide state we borrow, held for as long as the call runs and
        // handed back in endCall(). Routing choices do not stick outside that mode, so the speaker
        // preference has to be applied after it, not before.
        //
        // A video call is looked at, not held to an ear, so it starts on the loudspeaker; a headset
        // still wins over both built-in outputs when one is connected.
        audioDeviceController.start(preferLoudspeaker = !callData.isAudioCall)

        coroutineScope {
            val joinObservers = startJoinObservers(joined)
            connectMediaAndObserve(joined)?.let { failure ->
                joinObservers.cancel()
                fail(failure)
            }
        }
    }

    /** What the call reports from the join on, media or not: its status and the audio route. */
    private fun CoroutineScope.startJoinObservers(rtcCall: MatrixRtcCall): Job = launch(start = CoroutineStart.UNDISPATCHED) {
        observeStatus(rtcCall)
        // Observed rather than echoed back from selectAudioDevice, because the route also changes
        // without us asking: a headset is unplugged, or the platform overrides the choice.
        launch(start = CoroutineStart.UNDISPATCHED) {
            audioDeviceController.devices.collect { value -> updateState { it.copy(audioDevices = value.toImmutableList()) } }
        }
        launch(start = CoroutineStart.UNDISPATCHED) {
            audioDeviceController.selectedDevice.collect { value -> updateState { it.copy(selectedAudioDevice = value) } }
        }
    }

    /** Media, the microphone, the routing and the observers. @return what failed, or null. */
    private suspend fun CoroutineScope.connectMediaAndObserve(joined: MatrixRtcCall): String? {
        val connected = joined.connectMedia().getOrElse {
            return "Media failed: ${it.message}"
        }
        call = connected
        connected.setComposedTiles(composedTiles)
        detailWindow?.let { connected.setDetailWindow(it) }
        rankingThreshold?.let { connected.setRankingThreshold(it) }
        // A child of the session scope rather than this coroutine: the shared flows have to outlive
        // any one tile's collection, and are torn down with the call in endCall().
        videoSharingScope = CoroutineScope(scope.coroutineContext + SupervisorJob())

        // Published in whatever state the microphone button is in. It has been on screen since before
        // the permission was asked for, and a tap there while the call connects has nowhere to go but
        // the snapshot - see setMicrophoneMuted - so this is where that intent is spent, on a track
        // the core publishes already muted.
        connected.publishMicrophone(muted = _state.value?.isMicrophoneMuted == true).onFailure {
            return "Microphone failed: ${it.message}"
        }
        Timber.i("ElementCall: connected as ${connected.localMemberId}")

        // Read before the observers start, because they publish the call's own camera state - off,
        // it has not been asked to capture yet - over the state the call is joining with.
        val wantsCamera = _state.value?.isCameraEnabled == true

        startObservers(joined, connected)

        // A video call starts with the camera on. The caller asked for video and the callee
        // answered a notification that said video, so making them find the camera button is
        // asking a question they already answered - unless they answered it again by turning the
        // camera off while the call connected, which is what the snapshot then says.
        //
        // Only when the permission is already in hand: if it arrives later,
        // setCameraPermissionGranted turns the camera on itself, which covers the other
        // ordering. Nothing here waits for it - a video call whose camera is refused is still a
        // working call.
        if (wantsCamera && _state.value?.isCameraPermissionGranted == true) {
            connected.setCameraEnabled(true)
                .onFailure { Timber.w(it, "ElementCall: could not start the camera the call joins with") }
        }
        return null
    }

    /**
     * Follow where the call is (feature-hq 030 C3). The core says when it is connected, from when, and when it is
     * over; a DM's ring and the end it brings included.
     */
    private fun CoroutineScope.observeStatus(rtcCall: MatrixRtcCall): Job = launch(start = CoroutineStart.UNDISPATCHED) {
        rtcCall.status.collect { status ->
            // From the ring going out until it stops ringing, whatever stops it (030 R23).
            val isRinging = status is MatrixRtcCallStatus.Calling && status.ring is MatrixRtcRingPhase.Ringing
            if (isRinging) soundEffectRenderer?.startLoop(CallSoundEffect.RINGBACK) else soundEffectRenderer?.stopLoop()
            when (status) {
                MatrixRtcCallStatus.Connecting -> updateState { it.copy(connection = ElementCallConnection.ConnectingMedia) }
                is MatrixRtcCallStatus.Calling -> updateState {
                    it.copy(connection = ElementCallConnection.Calling(status.callee, isRinging = status.ring is MatrixRtcRingPhase.Ringing))
                }
                is MatrixRtcCallStatus.Connected -> updateState {
                    it.copy(
                        connection = if (status.degraded) ElementCallConnection.Degraded else ElementCallConnection.Connected,
                        connectedAtElapsedMs = it.connectedAtElapsedMs ?: elapsedRealtimeAt(status.sinceMs),
                    )
                }
                is MatrixRtcCallStatus.Ended -> {
                    Timber.i("ElementCall: the call ended, ${status.reason}")
                    joinLeaveSoundPolicy?.silence()
                    raisedHandSoundPolicy?.silence()
                    // The caller is told, and dismisses it (030 R4); every other end closes the screen at once.
                    if (status.reason == MatrixRtcCallEnd.RingNotSent) {
                        updateState { it.copy(connection = ElementCallConnection.RingNotSent((it.connection as? ElementCallConnection.Calling)?.callee)) }
                        return@collect
                    }
                    // Likewise said until the user closes it (034 R27); the core has already left.
                    val lost = status.reason as? MatrixRtcCallEnd.ConnectionClosed
                    if (lost != null) {
                        fail(ElementCallFailure.ConnectionLost(lost.reason), "Connection lost: ${lost.message}")
                        return@collect
                    }
                    updateState { it.copy(connection = ElementCallConnection.Ended) }
                    // Torn down from a coroutine of its own, not this one: endCall cancels the job this
                    // collector runs in, and a coroutine cannot wait for its own death. Never left again:
                    // the core has already left, or is leaving, on every end it reports.
                    scope.launch { endCall(leave = false) }
                }
            }
        }
    }

    private fun MatrixRtcCallEnd.endSound(): CallSoundEffect? = when (this) {
        MatrixRtcCallEnd.Declined -> CallSoundEffect.DECLINED
        MatrixRtcCallEnd.Unanswered -> CallSoundEffect.TIMEOUT
        else -> null
    }

    private fun MatrixRtcCallEnd.toEndReason(): ElementCallEndReason = when (this) {
        MatrixRtcCallEnd.Left -> ElementCallEndReason.LEFT
        MatrixRtcCallEnd.Declined -> ElementCallEndReason.DECLINED
        MatrixRtcCallEnd.Unanswered -> ElementCallEndReason.UNANSWERED
        MatrixRtcCallEnd.RingNotSent -> ElementCallEndReason.RING_NOT_SENT
        MatrixRtcCallEnd.OthersLeft -> ElementCallEndReason.OTHERS_LEFT
        MatrixRtcCallEnd.Empty -> ElementCallEndReason.EMPTY
        MatrixRtcCallEnd.SlotClosed -> ElementCallEndReason.SLOT_CLOSED
        is MatrixRtcCallEnd.ConnectionClosed -> ElementCallEndReason.CONNECTION_CLOSED
    }

    /** The core's wall-clock moment on the monotonic clock durations are counted on. */
    private fun elapsedRealtimeAt(epochMs: Long): Long {
        val ago = (platform.currentTimeMs() - epochMs).coerceAtLeast(0)
        return platform.elapsedRealtimeMs() - ago
    }

    /**
     * Fan the call's flows into the snapshot, for as long as the calling scope lives.
     *
     * Every collector is started [CoroutineStart.UNDISPATCHED], so it runs synchronously up to its
     * `collect` and is subscribed by the time this returns. That is the whole point: a dispatched
     * launch would only be *queued* here, leaving the caller free to announce a connected call to a
     * screen while nothing was yet listening to the events that call raises.
     */
    @OptIn(FlowPreview::class)
    private fun CoroutineScope.startObservers(rtcCall: MatrixRtcCall, call: MatrixRtcMediaSession) {
        fun observe(block: suspend () -> Unit) = launch(start = CoroutineStart.UNDISPATCHED) { block() }

        observe { rtcCall.memberCount.collect { value -> updateState { it.copy(memberCount = value) } } }
        observe { call.tiles.collect { value -> updateState { it.copy(roster = value) } } }
        observe {
            call.localState
                .map { it?.tile }
                .distinctUntilChanged()
                .collect { value -> updateState { it.copy(ownTile = value) } }
        }
        // Sampled, because the meter publishes ten times a second *per member*: unsampled, an
        // eleven-person call produced over a hundred snapshots a second from this one source, each of
        // them a new state and a recomposition of every tile. Ten a second in total is all a meter
        // needs, and nothing but the diagnostics meters reads the levels - the tiles use the core's
        // speaking flag.
        observe {
            call.audioLevels
                .sample(AUDIO_LEVEL_SAMPLE_MS.milliseconds)
                .collect { value -> updateState { it.copy(audioLevels = value.toImmutableMap()) } }
        }
        observe { call.receiveStats.collect { value -> updateState { it.copy(receiveStats = value.toImmutableMap()) } } }
        observe { call.isMicrophoneMuted.collect { value -> updateState { it.copy(isMicrophoneMuted = value) } } }
        observe { call.isAudioTestToneEnabled.collect { value -> updateState { it.copy(isAudioTestToneEnabled = value) } } }
        observe { call.isCameraEnabled.collect { value -> updateState { it.copy(isCameraEnabled = value) } } }
        // Observed rather than set when we ask, because the user can end a share from the system's
        // cast notification without the app being involved at all - so this flag has a source of
        // truth outside anything we called.
        observe { call.isScreenSharing.collect { value -> updateState { it.copy(isScreenSharing = value) } } }
        // Observed rather than set when we ask for a switch: the camera has to close and another
        // open, so the answer arrives well after switchCamera() has returned.
        observe { call.isFrontCamera.collect { value -> updateState { it.copy(isFrontCamera = value) } } }
        observe {
            call.events.collect { event ->
                Timber.d("ElementCall: $event")
                when (event) {
                    is MatrixRtcCallEvent.FrameEncryption -> updateState {
                        it.copy(frameEncryption = (it.frameEncryption + (event.memberId to event.state)).toImmutableMap())
                    }
                    is MatrixRtcCallEvent.ParticipantJoined -> playJoinLeaveSound(CallSoundEffect.JOIN, event.memberId, event.participantCount)
                    is MatrixRtcCallEvent.ParticipantLeft -> playJoinLeaveSound(CallSoundEffect.LEAVE, event.memberId, event.participantCount)
                    is MatrixRtcCallEvent.HandRaised -> playRaisedHandSound(event.memberId)
                    else -> Unit
                }
            }
        }
    }

    /** One line per announced change, played or not, so a rageshake shows how often a membership lapses and returns. */
    private fun playJoinLeaveSound(sound: CallSoundEffect, memberId: String, participantCount: Int) {
        val plays = joinLeaveSoundPolicy?.shouldPlay(sound, participantCount, platform.elapsedRealtimeMs()) == true
        val change = if (sound == CallSoundEffect.JOIN) "joined" else "left"
        Timber.i("ElementCall: $memberId $change, $participantCount in the call: ${if (plays) "playing" else "silent"}")
        if (plays) soundEffectRenderer?.play(sound)
    }

    private fun playRaisedHandSound(memberId: String) {
        val isOneToOne = _state.value?.isOneToOne == true
        val plays = raisedHandSoundPolicy?.shouldPlay(isOneToOne, platform.elapsedRealtimeMs()) == true
        Timber.i("ElementCall: $memberId raised a hand: ${if (plays) "playing" else "silent"}")
        if (plays) soundEffectRenderer?.play(CallSoundEffect.RAISE_HAND)
    }

    private fun fail(message: String) = fail(ElementCallFailure.Other(message), message)

    private fun fail(failure: ElementCallFailure, message: String) {
        Timber.w("ElementCall: $message")
        _state.update { it?.copy(connection = ElementCallConnection.Failed(failure)) }
        // Not torn down here: the message is the whole point of the failed state, and clearing the
        // snapshot would take it off screen before it could be read. The host dismisses the call.
    }

    /**
     * Release everything, in the reverse order it was taken.
     *
     * Safe to call twice and safe to call with no call running - both happen, because a call can end
     * from the far end and from the user's hang-up at the same moment.
     */
    private suspend fun endCall(leave: Boolean) {
        val leavingRoom: MatrixRtcRoom?
        val leavingRtcCall: MatrixRtcCall?
        val leavingCall: MatrixRtcMediaSession?
        val endedCallData: ElementCallData?
        val endingSoundEffectRenderer: CallSoundEffectRenderer?
        mutex.withLock {
            if (_state.value == null && callJob == null) return
            Timber.i("ElementCall: ending call (leave=$leave)")
            // First: nothing below may sound like people leaving.
            joinLeaveSoundPolicy?.silence()
            raisedHandSoundPolicy?.silence()
            endedCallData = _state.value?.callData
            callJob?.cancel()
            callJob = null
            roomJob?.cancel()
            roomJob = null
            // Before the call handle goes: the shared flows read through it, and a surviving
            // subscriber would otherwise be left collecting from a disconnected session.
            videoSharingScope?.cancel()
            videoSharingScope = null
            sharedVideoFlows.clear()
            leavingRoom = rtcRoom
            leavingRtcCall = rtcCall
            leavingCall = call
            rtcRoom = null
            rtcCall = null
            call = null
            joinLeaveSoundPolicy = null
            raisedHandSoundPolicy = null
            soundEffectRenderer?.stopLoop()
            endingSoundEffectRenderer = soundEffectRenderer
            soundEffectRenderer = null
            // The next call's screen declares afresh; a window from this one would be about tiles
            // that no longer exist.
            composedTiles = emptySet()
            detailWindow = null
            _pictureInPictureSource.value = null
            _pictureInPictureEntry.value = null
            _state.value = null
        }

        // Outside the lock: leaving talks to the homeserver, and holding the lock across it would
        // block a start for the next call behind a network round trip.
        leavingCall?.disconnect()
        leavingCall?.close()
        // Read before leaving, which ends it as Left: any other end is one the core has already left on.
        val coreEnd = (leavingRtcCall?.status?.value as? MatrixRtcCallStatus.Ended)?.reason
        if (leavingRtcCall != null) {
            if (leave && coreEnd == null) leavingRtcCall.leave(MatrixRtcLeaveReason(code = HANGUP_REASON))
            leavingRtcCall.close()
        }
        // Last: the leave goes through the room.
        leavingRoom?.shutdown()

        // The screen has closed already; the sound plays to its end before the call's audio goes (030 R25).
        coreEnd?.endSound()?.let { endingSoundEffectRenderer?.playToEnd(it) }
        endingSoundEffectRenderer?.release()
        val isNextCallRunning = mutex.withLock { _state.value != null }
        // A call started while the sound played holds the route, the focus and the service now.
        if (!isNextCallRunning) {
            audioDeviceController.stop()
            audioFocus.releaseAudioFocus()
            platform.stopForegroundService()
        }
        // Clears the host's active call, and stops a ring that never got answered - hanging up from
        // the call screen while the far end is still ringing us is a real sequence, not a hypothetical.
        lifecycleListener.onCallEnded(endedCallData, coreEnd?.toEndReason() ?: ElementCallEndReason.LEFT)
    }

    internal companion object {
        const val HANGUP_REASON = "m.hangup"

        /**
         * How long a member's video stream stays open after the last tile drawing it goes away.
         *
         * Not a nicety - without it, moving a member between the spotlight and the strip closes and
         * reopens their stream. Compose disposes the old tile before composing the new one, so the
         * subscriber count touches zero in between, and a bare `WhileSubscribed()` reads that as
         * "nobody is watching" and tears the stream down. On device that meant a `stream.close()`
         * racing a frame already in flight, which released the decoder underneath libwebrtc's
         * `AndroidVideoDecoder` output thread and aborted the process inside
         * `VideoSinkWrapper::on_frame` - reliably, whenever a third participant joined and the
         * spotlight moved.
         *
         * Short enough that a call minimized or a member leaving still stops decoding promptly,
         * which is the property the cold flow exists for.
         */
        const val VIDEO_STREAM_LINGER_MS = 3_000L

        /** How often audio levels reach the state, for everyone at once. See `startObservers`. */
        const val AUDIO_LEVEL_SAMPLE_MS = 100L

        /**
         * How long to wait for the room context before deciding whether the far end rings. The
         * turnkey provider answers from the room info, which is immediate; a host's cache is too.
         */
        const val ROOM_CONTEXT_TIMEOUT_MS = 1_500L
    }
}
