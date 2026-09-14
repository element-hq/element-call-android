/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.callnative.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.call.api.CallData
import io.element.android.features.call.api.CurrentCall
import io.element.android.features.call.api.CurrentCallTracker
import io.element.android.features.call.api.RingingCallTracker
import io.element.android.libraries.audio.api.AudioFocus
import io.element.android.libraries.audio.api.AudioFocusRequester
import io.element.android.libraries.audio.api.CallAudioDevice
import io.element.android.libraries.audio.api.CallAudioDeviceController
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.matrix.api.notification.RtcNotificationType
import io.element.android.libraries.matrix.api.room.roomMembers
import io.element.android.libraries.matrixrtc.api.MatrixRtcCall
import io.element.android.libraries.matrixrtc.api.MatrixRtcCallEvent
import io.element.android.libraries.matrixrtc.api.MatrixRtcElementCallCompat
import io.element.android.libraries.matrixrtc.api.MatrixRtcLeaveReason
import io.element.android.libraries.matrixrtc.api.MatrixRtcNotify
import io.element.android.libraries.matrixrtc.api.MatrixRtcScreenCaptureToken
import io.element.android.libraries.matrixrtc.api.MatrixRtcServiceProvider
import io.element.android.libraries.matrixrtc.api.MatrixRtcSession
import io.element.android.libraries.matrixrtc.api.MatrixRtcStreamKind
import io.element.android.libraries.matrixrtc.api.MatrixRtcTransport
import io.element.android.libraries.matrixrtc.api.MatrixRtcVideoConstraints
import io.element.android.libraries.matrixrtc.api.MatrixRtcVideoFrame
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import io.element.android.services.appnavstate.api.AppForegroundStateService
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
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
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * Owns the one native call that can be running, for as long as it runs.
 *
 * This exists because a call is not a screen. The spike drove everything from a presenter inside
 * `NativeCallActivity`, which made the Activity's composition the call's lifetime: navigating away
 * ended the call, so the only shape the UI could take was a full-screen one in its own task - the
 * very limitation that makes the Element Call WebView awkward to embed. Holding the session here, on
 * [AppCoroutineScope], means the call is a fact about the app rather than about whatever is on
 * screen, and the full-screen UI and the minimized bar become two renderings of it.
 *
 * Single call by construction: [startCall] refuses while one is running. Multi-call would need a map
 * keyed by room, and nothing in the product asks for it yet.
 *
 * Everything that mutates goes through [mutex] on [appCoroutineScope], so callers can fire and
 * forget from any thread and the UI only ever sees whole snapshots.
 */
@SingleIn(AppScope::class)
@Inject
class NativeCallController(
    @AppCoroutineScope private val appCoroutineScope: CoroutineScope,
    private val platform: NativeCallPlatform,
    private val matrixClientProvider: MatrixClientProvider,
    private val rtcServiceProvider: MatrixRtcServiceProvider,
    private val audioDeviceController: CallAudioDeviceController,
    private val appPreferencesStore: AppPreferencesStore,
    private val currentCallTracker: CurrentCallTracker,
    private val ringingCallTracker: RingingCallTracker,
    private val appForegroundStateService: AppForegroundStateService,
    private val audioFocus: AudioFocus,
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow<NativeCallSnapshot?>(null)

    /** The call currently running, or null when there is none. */
    val state: StateFlow<NativeCallSnapshot?> = _state.asStateFlow()

    /**
     * Whether leaving the app should shrink the call into a floating window. See [NativeCallPip].
     *
     * A maximized call, or a minimized one with a picture in it. The second case completes the
     * handover the floating tile design implies: a minimized video call is already a tile floating
     * over our own app, so leaving the app should hand that tile to the system rather than making it
     * vanish. A minimized *audio* call is deliberately excluded - a floating window showing an avatar
     * over someone's launcher earns nothing, and the ongoing-call notification is the right
     * affordance for it.
     */
    val shouldEnterPictureInPicture: StateFlow<Boolean> = _state
        .map { it != null && (it.isMaximized || it.hasVideo) }
        .stateIn(appCoroutineScope, SharingStarted.Eagerly, false)

    private val _isInPictureInPicture = MutableStateFlow(false)

    /** Whether the app is currently a floating window, which only the Activity is told. */
    val isInPictureInPicture: StateFlow<Boolean> = _isInPictureInPicture.asStateFlow()

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
        // In init rather than per call: this is app-scoped and lives as long as the process, and a
        // null call resolves to false, which is the right answer between calls.
        appCoroutineScope.launch {
            combine(
                _state,
                _isInPictureInPicture,
                appForegroundStateService.isInForeground,
            ) { call, isInPip, isForeground ->
                call != null && call.isMaximized && !call.hasVideo && !isInPip && isForeground
            }
                .distinctUntilChanged()
                .collect { audioDeviceController.setProximityBlankingAllowed(it) }
        }
    }

    fun setInPictureInPicture(isInPictureInPicture: Boolean) {
        _isInPictureInPicture.value = isInPictureInPicture
        // Coming back out of PiP has to leave the call maximized rather than merely visible: PiP was
        // entered *from* the full-screen call, and dropping the user into a minimized bar over the
        // room list would be a different screen than the one they left.
        if (!isInPictureInPicture && _state.value != null) {
            setMaximized(true)
        }
    }

    /**
     * Edit the running call's snapshot, or do nothing if there is no call.
     *
     * Every collector in [startObservers] outlives the moment the call is torn down by a hair -
     * cancellation is not instantaneous - so "no call" is a normal thing for a late emission to
     * find, not an error.
     */
    private fun updateState(block: (NativeCallSnapshot) -> NativeCallSnapshot) {
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

    /** When the spotlight last moved, for the dwell in [nextSpotlight]. */
    private var spotlightChangedAtMs = 0L
    private var session: MatrixRtcSession? = null
    private var call: MatrixRtcCall? = null

    /**
     * Begin a call. Does nothing if one is already running, including for the same room: rejoining
     * would mint a new member id and leave the old membership behind as a ghost.
     *
     * Returns immediately. Nothing happens beyond entering [NativeCallConnection.RequestingPermission]
     * until the host answers with [setMicrophonePermissionGranted] - only an Activity can ask for a
     * permission, and this is not one.
     */
    fun startCall(callData: CallData) {
        appCoroutineScope.launch {
            mutex.withLock {
                if (_state.value != null) {
                    Timber.w("NativeCall: a call is already running, ignoring start for ${callData.roomId}")
                    return@withLock
                }
                Timber.i("NativeCall: call requested for ${callData.roomId}")
                _state.value = NativeCallSnapshot(
                    callData = callData,
                    connection = NativeCallConnection.RequestingPermission,
                )
                // Reported as soon as the call is requested rather than once it connects, so the room
                // header's Join button hides while we are still joining rather than blinking through
                // an interval where the app believes we are not in the call we are joining.
                currentCallTracker.onCallStarted(CurrentCall.RoomCall(callData.roomId))
                appForegroundStateService.updateIsInCallState(true)
                // Started here rather than after joining, because the bar is on screen from this
                // moment and a call labelled with a room id would be worse than one labelled late.
                roomJob = appCoroutineScope.launch { observeRoom(callData) }
            }
        }
    }

    /**
     * The MSC4075 notification this join should send, which is what makes the far end ring.
     *
     * A DM rings: there is exactly one other person, they are being called, and a phone ringing is
     * what that means. Any other room only gets a silent notification - ringing a room summons
     * everyone in it, and a group call is an invitation rather than a summons.
     *
     * Sent on every join we make, not only on the first: the core suppresses the notification once
     * anybody else is in the session, so joining a call already in progress rings nobody, and that
     * check belongs on the side that knows the session's membership rather than here.
     */
    private suspend fun notifyFor(client: MatrixClient, callData: CallData): MatrixRtcNotify {
        // Defaults to a group call rather than a ring if the room cannot be read: an unwanted ring
        // wakes people up, a missing one only makes the call quieter than it should have been.
        val isDm = client.getRoom(callData.roomId)?.use { it.isDm() } == true
        return MatrixRtcNotify(
            type = if (isDm) RtcNotificationType.RING else RtcNotificationType.NOTIFY,
            intent = if (callData.isAudioCall) CallIntent.AUDIO else CallIntent.VIDEO,
        )
    }

    /**
     * Watch the room the call is in, for the two things the call needs from it: what to call the
     * call, and who its participants are.
     *
     * The RTC layer knows participants only as member ids and user ids - it has no idea what anyone
     * is called or what they look like, and no reason to. Names and avatars come from the room, and
     * joining the two up is this class's job rather than the UI's, so that the bar and the call
     * screen do not each have to do it.
     */
    private suspend fun observeRoom(callData: CallData) {
        val client = matrixClientProvider.getOrRestore(callData.sessionId).getOrNull() ?: return
        client.getRoom(callData.roomId)?.use { room ->
            coroutineScope {
                launch { room.roomInfoFlow.collect { info -> updateState { it.copy(roomName = info.name, isDm = info.isDm) } } }
                launch {
                    room.membersStateFlow.collect { members ->
                        val byUserId = members.roomMembers().orEmpty().associateBy { member -> member.userId }
                        updateState { it.copy(roomMembers = byUserId.toImmutableMap()) }
                    }
                }
                // The member list is lazily loaded, and a call is exactly the moment it is needed:
                // without this the tiles would show user ids until something else happened to ask.
                room.updateMembers()
            }
        }
    }

    /**
     * Answer the microphone permission request. Denial fails the call: it is audio first, and
     * `AudioRecord` would fail anyway.
     */
    fun setMicrophonePermissionGranted(granted: Boolean) {
        appCoroutineScope.launch {
            mutex.withLock {
                val current = _state.value ?: return@withLock
                if (!granted) {
                    Timber.i("NativeCall: microphone permission denied")
                    updateState { it.copy(connection = NativeCallConnection.Failed("Microphone permission denied")) }
                    return@withLock
                }
                // The host re-reports a permission it already has whenever it is recreated, so this
                // has to be idempotent or a configuration change would join the call a second time.
                if (current.isMicrophonePermissionGranted) return@withLock
                updateState { it.copy(isMicrophonePermissionGranted = true) }

                // Both must be in place before capture starts: the service so the microphone survives
                // backgrounding, the audio focus so other apps duck.
                platform.startForegroundService()
                audioFocus.requestAudioFocus(AudioFocusRequester.ElementCall) {}

                callJob = appCoroutineScope.launch { runCall(current.callData) }
            }
        }
    }

    fun setCameraPermissionGranted(granted: Boolean) {
        appCoroutineScope.launch {
            updateState { it.copy(isCameraPermissionGranted = granted) }
            if (!granted) return@launch
            // Started again, not for the first time: the service is already running for the
            // microphone, and this is what lets it add the camera type it could not claim before.
            // Without it, capture stops the moment the call is backgrounded.
            platform.startForegroundService()
            // Turned on as soon as it is granted, because the only way to get here is having just
            // asked for it - which only happens when the user tapped the camera button. Making them
            // tap twice would be an odd reward for saying yes.
            call?.setCameraEnabled(true)
        }
    }

    fun setMicrophoneMuted(muted: Boolean) {
        // Muting reaches the transport as well as capture, so it suspends.
        appCoroutineScope.launch { call?.setMicrophoneMuted(muted) }
    }

    fun setCameraEnabled(enabled: Boolean) {
        appCoroutineScope.launch { call?.setCameraEnabled(enabled) }
    }

    fun switchCamera() {
        appCoroutineScope.launch { call?.switchCamera() }
    }

    fun setAudioTestToneEnabled(enabled: Boolean) {
        call?.setAudioTestToneEnabled(enabled)
    }

    /** Send call audio to [device]. The list to choose from is [NativeCallSnapshot.audioDevices]. */
    fun selectAudioDevice(device: CallAudioDevice) {
        audioDeviceController.select(device)
    }

    fun setMaximized(maximized: Boolean) {
        updateState { it.copy(isMaximized = maximized) }
    }

    /** Show or hide the per-tile debug readout. See [NativeCallSnapshot.isTileStatsVisible]. */
    fun toggleTileStats() {
        updateState { it.copy(isTileStatsVisible = !it.isTileStatsVisible) }
    }

    /**
     * Video frames for one member's stream, or an empty flow when no call is running.
     *
     * Shared rather than handed straight through, because [MatrixRtcCall.videoFrames] is **cold and
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
    fun videoFrames(memberId: String, kind: MatrixRtcStreamKind = MatrixRtcStreamKind.CAMERA): Flow<MatrixRtcVideoFrame> {
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
     * Start or stop sharing the screen.
     *
     * The order here is the whole reason this lives in the controller rather than being passed
     * straight through: from Android 14 the foreground service has to *already* be running with the
     * `mediaProjection` type when the projection is claimed, and claiming happens inside
     * `setScreenShareEnabled`. Upgrading the service afterwards, or in parallel, is a
     * `SecurityException` from the platform rather than a warning.
     *
     * @param token the user's grant from the system dialog, which only an Activity can raise. Null
     * stops sharing.
     */
    fun setScreenShareEnabled(token: MatrixRtcScreenCaptureToken?) {
        appCoroutineScope.launch {
            val currentCall = call ?: return@launch
            if (token != null) {
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
     * Tell the core how big a member's video is actually being drawn.
     *
     * Driven from the tile that draws it, because the layout is the only thing that knows. Without
     * this every tile receives the sender's best simulcast layer whatever size it is shown at - a
     * 100dp thumbnail decoding and compositing 720p, which is most of what the phone was doing.
     */
    fun setVideoConstraints(memberId: String, kind: MatrixRtcStreamKind, constraints: MatrixRtcVideoConstraints) {
        appCoroutineScope.launch { call?.setVideoConstraints(memberId, kind, constraints) }
    }

    /** End the call and publish a leave membership. */
    fun hangUp() {
        appCoroutineScope.launch { endCall(leave = true) }
    }

    private suspend fun runCall(callData: CallData) {
        updateState { it.copy(connection = NativeCallConnection.Joining) }
        Timber.i("NativeCall: joining ${callData.roomId}")

        val matrixClient = matrixClientProvider.getOrRestore(callData.sessionId).getOrElse {
            fail("No session: ${it.message}")
            return
        }
        val rtcService = rtcServiceProvider.provide(matrixClient)

        val transport = rtcService.discoverTransports()
            .getOrElse {
                fail("Transport discovery failed: ${it.message}")
                return
            }
            .also { Timber.i("NativeCall: discovered transports=$it") }
            .filterIsInstance<MatrixRtcTransport.LiveKit>()
            .firstOrNull()
        if (transport == null) {
            fail("Homeserver offers no LiveKit transport")
            return
        }

        // Read once, here, rather than observed: the mode is fixed for the lifetime of a session -
        // it decides the member id, the SFU identity and the token endpoint as well as the wire
        // format - so changing it mid-call is not something the core could act on. Changing it in
        // developer settings therefore applies to the next call.
        //
        // Temporary: pinned to the state-event mode while the app builds against the released Rust SDK.
        // The two sticky modes need MSC4354, which the widget-driver stopgap cannot carry - see
        // `libraries/rustrtc/FEEDBACK.md`, "Widget-driver stopgap". The preference is still read so the
        // pin is visible in the log, and so that lifting it is a one-line change here.
        val preferredElementCallCompat = appPreferencesStore.getNativeCallElementCallCompatFlow().first()
        val elementCallCompat = MatrixRtcElementCallCompat.STATE_EVENTS
        if (preferredElementCallCompat != elementCallCompat) {
            Timber.i("NativeCall: Element Call compatibility $preferredElementCallCompat is not available on the released SDK, pinned to $elementCallCompat")
        }
        Timber.i("NativeCall: joining with Element Call compatibility $elementCallCompat")

        val joined = rtcService.joinSession(
            roomId = callData.roomId,
            slotId = DEFAULT_SLOT_ID,
            transport = transport,
            elementCallCompat = elementCallCompat,
            notify = notifyFor(matrixClient, callData),
        ).getOrElse {
            fail("Join failed: ${it.message}")
            return
        }
        session = joined

        updateState { it.copy(connection = NativeCallConnection.ConnectingMedia) }
        val connected = joined.connectMedia(transport).getOrElse {
            fail("Media failed: ${it.message}")
            return
        }
        call = connected
        // A child of the app scope rather than this coroutine: the shared flows have to outlive any
        // one tile's collection, and are torn down with the call in endCall().
        videoSharingScope = CoroutineScope(appCoroutineScope.coroutineContext + SupervisorJob())

        connected.publishMicrophone().onFailure {
            fail("Microphone failed: ${it.message}")
            return
        }
        Timber.i("NativeCall: connected as ${connected.localMemberId}")

        // Communication mode is device-wide state we borrow, held for as long as the call runs and
        // handed back in endCall(). Routing choices do not stick outside that mode, so the speaker
        // preference has to be applied after it, not before.
        //
        // A video call is looked at, not held to an ear, so it starts on the loudspeaker; a headset
        // still wins over both built-in outputs when one is connected.
        audioDeviceController.start(preferLoudspeaker = !callData.isAudioCall)

        coroutineScope {
            // Subscribed before the call is announced as connected, never after. The core's event
            // flow has no replay, so anything raised in the gap between "connected" and "listening"
            // is gone - and the event that matters most, Ended, arrives exactly when a call is
            // short-lived enough for that gap to catch it.
            startObservers(joined, connected)

            // Whatever was ringing for this call has now been answered. Until this lands the
            // ringtone keeps playing over the connected call, the full-screen incoming UI stays up,
            // and the missed-call timeout still fires - so answering looks like it failed while
            // actually having worked. A no-op for an outgoing call, which was never ringing.
            ringingCallTracker.onCallJoined(callData)

            // A video call starts with the camera on. The caller asked for video and the callee
            // answered a notification that said video, so making them find the camera button is
            // asking a question they already answered.
            //
            // Only when the permission is already in hand: if it arrives later,
            // setCameraPermissionGranted turns the camera on itself, which covers the other
            // ordering. Nothing here waits for it - a video call whose camera is refused is still a
            // working call.
            if (!callData.isAudioCall && _state.value?.isCameraPermissionGranted == true) {
                connected.setCameraEnabled(true)
                    .onFailure { Timber.w(it, "NativeCall: could not start the camera for a video call") }
            }

            updateState {
                it.copy(
                    connection = NativeCallConnection.Connected,
                    connectedAtElapsedMs = platform.elapsedRealtimeMs(),
                )
            }
            // coroutineScope holds here until the observers are cancelled, which is what teardown does.
        }
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
    private fun CoroutineScope.startObservers(session: MatrixRtcSession, call: MatrixRtcCall) {
        fun observe(block: suspend () -> Unit) = launch(start = CoroutineStart.UNDISPATCHED) { block() }

        // The core's count query rather than members.size: the projection behind members can sit at
        // zero for a whole call. See MatrixRtcSession.memberCount.
        observe { session.memberCount.collect { value -> updateState { it.copy(memberCount = value) } } }
        observe {
            call.participants.collect { value ->
                updateState {
                    val participants = value.toImmutableList()
                    val withParticipants = it.copy(participants = participants)
                    // Recomputed here too, not only on speaker changes: the spotlighted member can
                    // leave, and holding a spotlight on someone who is gone would leave the big tile
                    // showing nothing at all.
                    withParticipants.copy(spotlightMemberId = withParticipants.nextSpotlight(it.activeSpeakerIds))
                }
            }
        }
        // Sampled, because the meter publishes ten times a second *per member*: unsampled, an
        // eleven-person call produced over a hundred snapshots a second from this one source, each of
        // them a new state and a recomposition of every tile. Ten a second in total is all a meter
        // needs, and nothing but the diagnostics meters reads the levels - the tiles use the SFU's
        // active speakers.
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
        // Observed rather than echoed back from selectAudioDevice, because the route also changes
        // without us asking: a headset is unplugged, or the platform overrides the choice.
        observe { audioDeviceController.devices.collect { value -> updateState { it.copy(audioDevices = value.toImmutableList()) } } }
        observe { audioDeviceController.selectedDevice.collect { value -> updateState { it.copy(selectedAudioDevice = value) } } }
        observe {
            call.events.collect { event ->
                // Active speakers arrive several times a second and are on screen anyway, so they
                // would only bury the events that say something happened.
                if (event !is MatrixRtcCallEvent.ActiveSpeakers) Timber.d("NativeCall: $event")
                when (event) {
                    is MatrixRtcCallEvent.MediaConnectionDegraded -> updateState {
                        it.copy(connection = if (event.degraded) NativeCallConnection.Degraded else NativeCallConnection.Connected)
                    }
                    is MatrixRtcCallEvent.ActiveSpeakers -> updateState {
                        val speakers = event.speakers.map { speaker -> speaker.memberId }.toImmutableSet()
                        it.copy(activeSpeakerIds = speakers, spotlightMemberId = it.nextSpotlight(speakers))
                    }
                    is MatrixRtcCallEvent.FrameEncryption -> updateState {
                        it.copy(frameEncryption = (it.frameEncryption + (event.memberId to event.state)).toImmutableMap())
                    }
                    is MatrixRtcCallEvent.Ended -> {
                        updateState { it.copy(connection = NativeCallConnection.Ended) }
                        // Torn down from a coroutine of its own, not this one: endCall cancels the
                        // job this collector runs in, and a coroutine cannot wait for its own death.
                        // The far end has already ended the session, so there is nothing to leave.
                        appCoroutineScope.launch { endCall(leave = false) }
                    }
                    else -> Unit
                }
            }
        }
    }

    /**
     * Who should hold the spotlight, given who is speaking now.
     *
     * Sticky on purpose. The rule is not "whoever is talking" but "whoever was last worth switching
     * to", and a switch is only allowed once [SPOTLIGHT_MIN_DWELL_MS] has passed since the previous
     * one. Two people talking over each other produce several `ActiveSpeakers` events a second, and
     * without the dwell the big tile follows every one of them.
     *
     * That is not only unwatchable. Because the strip excludes whoever is spotlighted, each change
     * disposes one video tile and composes another - so a flickering spotlight created and destroyed
     * renderers, and the decoder threads behind them, several times a second. Roughly a thousand
     * threads into a call, one of them found a stale JNI thread-local and aborted the process inside
     * libwebrtc's `AttachCurrentThreadIfNeeded`.
     *
     * Never ourselves: we are in the strip, and spotlighting us would draw the same person twice.
     */
    private fun NativeCallSnapshot.nextSpotlight(speakers: Set<String>): String? {
        val remotes = participants.filterNot { it.isLocal }
        if (remotes.isEmpty()) return null

        val current = remotes.firstOrNull { it.memberId == spotlightMemberId }
        val candidate = remotes.firstOrNull { it.memberId in speakers } ?: current ?: remotes.first()
        if (candidate.memberId == spotlightMemberId) return spotlightMemberId

        val now = platform.elapsedRealtimeMs()
        // A spotlight that has gone stale - its member left - is replaced at once. Waiting out the
        // dwell there would leave the big tile on someone who is no longer in the call.
        if (current != null && now - spotlightChangedAtMs < SPOTLIGHT_MIN_DWELL_MS) return spotlightMemberId

        spotlightChangedAtMs = now
        return candidate.memberId
    }

    private fun fail(message: String) {
        Timber.w("NativeCall: $message")
        _state.update { it?.copy(connection = NativeCallConnection.Failed(message)) }
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
        val leavingSession: MatrixRtcSession?
        val leavingCall: MatrixRtcCall?
        val endedCallData: CallData?
        mutex.withLock {
            if (_state.value == null && callJob == null) return
            Timber.i("NativeCall: ending call (leave=$leave)")
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
            leavingSession = session
            leavingCall = call
            session = null
            call = null
            _state.value = null
        }

        // Outside the lock: leaving talks to the homeserver, and holding the lock across it would
        // block a start for the next call behind a network round trip.
        leavingCall?.disconnect()
        leavingCall?.close()
        if (leavingSession != null) {
            if (leave) leavingSession.leave(MatrixRtcLeaveReason(code = HANGUP_REASON))
            leavingSession.close()
        }

        audioDeviceController.stop()
        audioFocus.releaseAudioFocus()
        platform.stopForegroundService()
        currentCallTracker.onCallEnded()
        // Clears the active call, and stops a ring that never got answered - hanging up from the
        // call screen while the far end is still ringing us is a real sequence, not a hypothetical.
        endedCallData?.let { ringingCallTracker.onCallEnded(it) }
        appForegroundStateService.updateIsInCallState(false)
    }

    internal companion object {
        /**
         * MSC4143 slots are not readable from the SDK yet, so both ends of a spike call agree on a
         * fixed one. Revisit once slot state is exposed.
         *
         * The `m.call#` prefix is not decoration: MSC4143 requires a slot id to start with
         * `{applicationType}#`, and the bare `m.call` we used before is invalid. Nothing on our side
         * says so - the core validates this in `openSlot`, which we never call, and `join` accepts
         * whatever it is given - so the first thing to notice was Element Call refusing our
         * membership outright with "slot_id must start with m.call#" while our own logs showed a
         * perfectly healthy call.
         *
         * The suffix is `ROOM` because that is the slot Element Call opens for a room-wide call, and a
         * slot id is what decides whether two clients are in the same call at all. A different one
         * would leave us technically valid and still alone.
         */
        const val DEFAULT_SLOT_ID = "m.call#ROOM"
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
         * How long the spotlight stays put before it is allowed to move again.
         *
         * Long enough that a back-and-forth exchange does not swap the big tile on every syllable,
         * short enough that handing the floor to someone else is followed promptly.
         */
        const val SPOTLIGHT_MIN_DWELL_MS = 2_000L
    }
}
