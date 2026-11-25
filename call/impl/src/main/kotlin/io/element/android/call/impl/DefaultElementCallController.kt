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
import io.element.android.call.api.ElementCallInterruption
import io.element.android.call.api.ElementCallLifecycleListener
import io.element.android.call.api.ElementCallLobbyJoinLabel
import io.element.android.call.api.ElementCallOptions
import io.element.android.call.api.ElementCallPreferences
import io.element.android.call.api.ElementCallPreferencesStore
import io.element.android.call.api.ElementCallRoomContextProvider
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.ElementCallWindowRect
import io.element.android.call.api.analytics.ElementCallAnalytics
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
import io.element.android.call.api.rtc.MatrixRtcInterruptions
import io.element.android.call.api.rtc.MatrixRtcJoinException
import io.element.android.call.api.rtc.MatrixRtcLeaveReason
import io.element.android.call.api.rtc.MatrixRtcLocalCamera
import io.element.android.call.api.rtc.MatrixRtcMediaSession
import io.element.android.call.api.rtc.MatrixRtcMembershipFormat
import io.element.android.call.api.rtc.MatrixRtcReaction
import io.element.android.call.api.rtc.MatrixRtcReactionKind
import io.element.android.call.api.rtc.MatrixRtcRing
import io.element.android.call.api.rtc.MatrixRtcRingPhase
import io.element.android.call.api.rtc.MatrixRtcRoom
import io.element.android.call.api.rtc.MatrixRtcScreenCaptureToken
import io.element.android.call.api.rtc.MatrixRtcSignallingHealth
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.MatrixRtcVideoCodec
import io.element.android.call.api.rtc.MatrixRtcVideoConstraints
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import io.element.android.call.api.rtc.id.UserId
import io.element.android.call.impl.analytics.ElementCallAnalyticsReporter
import io.element.android.call.impl.analytics.FailureReport
import io.element.android.call.impl.analytics.callKindOf
import io.element.android.call.impl.audio.CallSoundEffect
import io.element.android.call.impl.audio.CallSoundEffectRenderer
import io.element.android.call.impl.audio.GENERIC_REACTION_SOUND
import io.element.android.call.impl.audio.JoinLeaveSoundPolicy
import io.element.android.call.impl.audio.RaisedHandSoundPolicy
import io.element.android.call.impl.util.runCatchingExceptions
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
@Suppress("LargeClass") // The lobby is a phase of this one call, not a second object (feature-hq 045 C3).
internal class DefaultElementCallController(
    private val scope: CoroutineScope,
    private val platform: ElementCallPlatform,
    private val rtcClient: MatrixRtcClient,
    private val audioDeviceController: CallAudioDeviceController,
    private val audioFocus: AudioFocus,
    private val lifecycleListener: ElementCallLifecycleListener,
    private val roomContextProvider: ElementCallRoomContextProvider,
    private val options: ElementCallOptions,
    private val preferencesStore: ElementCallPreferencesStore,
    private val analytics: ElementCallAnalytics,
    /** Us, for our avatar before the core reports our tile (feature-hq 045 R9). */
    private val ownUserId: UserId,
) : ElementCallController {
    private val mutex = Mutex()

    private val _state = MutableStateFlow<ElementCallSnapshot?>(null)

    override val state: StateFlow<ElementCallSnapshot?> = _state.asStateFlow()

    override val isDeveloperModeEnabled: Boolean get() = options.isDeveloperModeEnabled

    private val storedPreferences = StoredElementCallPreferences(preferencesStore)

    override val preferences: StateFlow<ElementCallPreferences> = storedPreferences.value

    // Not replayed: a reaction nobody is drawing, in picture-in-picture or minimised, is gone (037 R8).
    private val _reactions = MutableSharedFlow<MatrixRtcReaction>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val reactions: Flow<MatrixRtcReaction> = _reactions.asSharedFlow()

    private var nextReactionId = 0L

    /**
     * A maximized call, or a minimized one with a picture in it. The second case completes the
     * handover the floating tile design implies: a minimized video call is already a tile floating
     * over our own app, so leaving the app should hand that tile to the system rather than making it
     * vanish. A minimized *audio* call is deliberately excluded - a floating window showing an avatar
     * over someone's launcher earns nothing, and the ongoing-call notification is the right
     * affordance for it. Never the lobby, which is not a call (feature-hq 045 R33).
     */
    override val shouldEnterPictureInPicture: StateFlow<Boolean> = _state
        .map { it != null && it.isMaximized && it.connection !is ElementCallConnection.Lobby }
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
        // blanking another app's screen is never ours to do. Never in the lobby, which nobody holds to an ear (045 C2).
        // The earpiece condition is the audio layer's own and is checked there.
        //
        // In init rather than per call: this lives as long as the session, and a null call resolves
        // to false, which is the right answer between calls.
        scope.launch {
            combine(
                _state,
                _isInPictureInPicture,
                lifecycleListener.isAppInForeground,
            ) { call, isInPip, isForeground ->
                call != null && call.connection !is ElementCallConnection.Lobby && call.isMaximized && !call.hasVideo && !isInPip && isForeground
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

    /** The running call's interruptions record, from its media session; null before media. */
    private val interruptionRecord = MutableStateFlow<MatrixRtcInterruptions?>(null)

    /** Publishes the held entries once it has waited out [INTERRUPTION_HOLD]. */
    private var interruptionHoldJob: Job? = null

    /**
     * Signalling, and the list's entries, for the life of the call. Signalling is unreachable if either source says so: the
     * client's, the only one before media, or the record's copy; the two agree a hop apart, and taking the worse keeps that
     * hop from re-enabling the hand.
     */
    private suspend fun followInterruptions() = coroutineScope {
        interruptionRecord.value = null
        interruptionHoldJob = null
        combine(rtcClient.signallingHealth(), interruptionRecord) { client, record ->
            val signalling = if (client is MatrixRtcSignallingHealth.Unreachable) client else record?.signalling ?: MatrixRtcSignallingHealth.Reachable
            signalling to ElementCallInterruption.entries(signalling, record)
        }
            .collect { (signalling, entries) ->
                updateState { it.copy(signalling = signalling) }
                holdInterruptions(entries)
            }
    }

    /**
     * The list shows nothing until some cause has lasted [INTERRUPTION_HOLD]: a blip shorter than that is nothing the user can
     * act on, and an indicator that comes up at every one is soon ignored (034 R35, R40). Once up it follows the causes at
     * once, and empties the moment none is left. The tiles never wait: what a tile shows is about that tile.
     */
    private fun CoroutineScope.holdInterruptions(entries: List<ElementCallInterruption>) {
        val shown = _state.value?.interruptions.orEmpty()
        when {
            entries.isEmpty() -> {
                interruptionHoldJob?.cancel()
                interruptionHoldJob = null
                if (shown.isNotEmpty()) updateState { it.copy(interruptions = persistentListOf()) }
            }
            shown.isNotEmpty() -> if (entries != shown) updateState { it.copy(interruptions = entries.toImmutableList()) }
            interruptionHoldJob == null -> interruptionHoldJob = launch {
                delay(INTERRUPTION_HOLD)
                interruptionHoldJob = null
                val signalling = _state.value?.signalling ?: MatrixRtcSignallingHealth.Reachable
                updateState { it.copy(interruptions = ElementCallInterruption.entries(signalling, interruptionRecord.value).toImmutableList()) }
            }
        }
    }

    /** When each member's current spell of being unable to decrypt reaches [KEYS_MISSING_AFTER]. */
    private val keySpells = mutableMapOf<String, Job>()

    /** Kept once it has fired, so a spell that outlasts the wait is not granted a second one. */
    private fun CoroutineScope.reconcileKeySpells(unableToDecrypt: Set<String>) {
        val ended = keySpells.keys - unableToDecrypt
        ended.forEach { keySpells.remove(it)?.cancel() }
        if (ended.isNotEmpty()) updateState { it.copy(membersMissingKeys = (it.membersMissingKeys - ended).toImmutableSet()) }
        for (memberId in unableToDecrypt - keySpells.keys) {
            keySpells[memberId] = launch {
                delay(KEYS_MISSING_AFTER)
                Timber.w("ElementCall: $memberId's keys have stayed away for $KEYS_MISSING_AFTER")
                updateState { it.copy(membersMissingKeys = (it.membersMissingKeys + memberId).toImmutableSet()) }
            }
        }
    }

    /** The join-and-observe coroutine for the running call. Cancelled as the first step of teardown. */
    private var callJob: Job? = null

    /** Separate from [callJob] because the room is wanted before the microphone is granted. */
    private var roomJob: Job? = null

    /** The running call's analytics (feature-hq 044), from [startCall] to [endCall]. */
    private var reporter: ElementCallAnalyticsReporter? = null

    /**
     * One shared video flow per member and stream kind, so however many tiles draw something, one
     * stream is open. Keyed by member id, which is minted fresh per join, so entries cannot outlive
     * their meaning - and by kind, because a member sharing their screen while their camera is on has
     * two independent streams that would otherwise collide on one key.
     */
    private val sharedVideoFlows = ConcurrentHashMap<VideoStreamKey, Flow<MatrixRtcVideoFrame>>()

    private var audioDevicesJob: Job? = null

    /** Hosts the sharing above. Tied to the media connection, since that is what the flows read. */
    private var videoSharingScope: CoroutineScope? = null

    private var rtcRoom: MatrixRtcRoom? = null
    private var rtcCall: MatrixRtcCall? = null

    /**
     * Whether the call joins with the camera on: the settled intent, or the user's taps before media (045 R15, R17). Not
     * the snapshot's `isCameraEnabled`, which follows the capturing camera once media is up.
     */
    private var joinsWithCamera = false

    /** Whether the user has turned the camera on or off before media. Their choice wins over the settled intent (045 C24). */
    private var isCameraChosenByUser = false

    /**
     * Whether the camera question the screen asks up front, on a call that asked for video, is still unanswered.
     * Its answer is not a tap on the camera button, so a grant then starts the camera only if the call joins with it.
     */
    private var isUpFrontCameraAnswerPending = false
    private var call: MatrixRtcMediaSession? = null
    private var joinLeaveSoundPolicy: JoinLeaveSoundPolicy? = null
    private var raisedHandSoundPolicy: RaisedHandSoundPolicy? = null
    private var soundEffectRenderer: CallSoundEffectRenderer? = null
    private var composedTiles: Set<MatrixRtcTileId> = emptySet()
    private var detailWindow: MatrixRtcDetailWindow? = null

    /** Not cleared with the call: it is the layout's, set once for the controller. */
    private var rankingThreshold: Int? = null

    /** The attempt's camera, from the lobby into the call (045 R49): created at [startCall], stopped in [endCall] and in [fail]. */
    private var camera: MatrixRtcLocalCamera? = null
    private val attemptCamera = MutableStateFlow<MatrixRtcLocalCamera?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val ownCameraFrames: Flow<MatrixRtcVideoFrame> = attemptCamera.flatMapLatest { it?.frames ?: emptyFlow() }

    /** Starts and stops the camera before media, one change at a time. */
    private val cameraMutex = Mutex()

    /** The facing, the outputs: what the attempt reports before media as well as in it. Cancelled in [endCall]. */
    private var attemptJob: Job? = null

    /** The lobby's room, intent and count, reloaded on a format change (045 C23). Cancelled at Join and in [endCall]. */
    private var lobbyJob: Job? = null

    /** Whether this attempt opened on the lobby, which keeps its camera running into the call (045 R49). */
    private var isFromLobby = false

    /** Reported by the screen; not cleared with the call, since the screen outlives no call but may precede one. */
    @Volatile
    private var isCallScreenVisible = false

    /** Whether a focus request of ours is outstanding, granted or not. */
    private var hasRequestedAudioFocus = false

    /** Whether the lobby holds the audio now: its camera runs only then (045 R48, C27). */
    @Volatile
    private var holdsAudioFocus = false
    private var hasStartedAudioDevices = false
    private var hasStartedForegroundService = false

    /** Whether the host was told [ElementCallLifecycleListener.onCallStarted]: a lobby closed without Join was not (045 R46). */
    private var hasReportedCallStarted = false

    /**
     * Begin a call, on the lobby in a group room that has not asked to skip it (feature-hq 045 R1-R5). Does nothing if
     * one is already running, including for the same room: rejoining would mint a new member id and leave the old
     * membership behind as a ghost. A lobby is not a call, so a start over one closes it first (R48).
     *
     * Returns immediately. Without a lobby, nothing happens beyond [ElementCallConnection.RequestingPermission]
     * until the host answers with [setMicrophonePermissionGranted] - only an Activity can ask for a permission.
     */
    override fun startCall(callData: ElementCallData) {
        scope.launch {
            if (_state.value?.connection is ElementCallConnection.Lobby) {
                Timber.i("ElementCall: a call was requested for ${callData.roomId} over a lobby, closing the lobby")
                // Awaited: the core frees a room only once it is shut, and the new call may be in the same room (045 C23).
                endCall(leave = false)
            }
            mutex.withLock {
                if (_state.value != null) {
                    Timber.w("ElementCall: a call is already running, ignoring start for ${callData.roomId}")
                    return@withLock
                }
                val showsLobby = callData.showsLobby
                Timber.i("ElementCall: call requested for ${callData.roomId}, ${if (showsLobby) "in a lobby" else "without a lobby"}")
                val camera = rtcClient.camera()
                this@DefaultElementCallController.camera = camera
                attemptCamera.value = camera
                isFromLobby = showsLobby
                _state.value = ElementCallSnapshot(
                    callData = callData,
                    connection = if (showsLobby) {
                        ElementCallConnection.Lobby(
                            memberCount = null,
                            hasSeenMembers = false
                        )
                    } else {
                        ElementCallConnection.RequestingPermission
                    },
                    isDm = callData.isDm,
                    ownUserId = ownUserId,
                    isScreenShareAvailable = options.isScreenSharingEnabled,
                    // Off until the core has settled the intent, never the host's guess (045 R59, C24). From then on this
                    // is the camera's desired state, and the tap that changes it is what the call joins on.
                    isCameraEnabled = false,
                    isCameraSwitchAvailable = platform.isCameraSwitchAvailable(),
                )
                joinsWithCamera = false
                isCameraChosenByUser = false
                isUpFrontCameraAnswerPending = false
                // Started here rather than after joining, because the bar is on screen from this
                // moment and a call labelled with a room id would be worse than one labelled late.
                roomJob = scope.launch { observeRoom(callData) }
                attemptJob = scope.launch { camera.isFrontFacing.collect { value -> updateState { it.copy(isFrontCamera = value) } } }
                if (showsLobby) {
                    // The lobby takes the call's audio at open, as Join does, and lists the call's outputs from it (045 R45, C27).
                    syncLobbyAudioFocus()
                    startAudioDevices(preferLoudspeaker = callData.intent != MatrixRtcCallIntent.AUDIO)
                    lobbyJob = scope.launch { followLobby(callData) }
                } else {
                    // Reported as soon as the call is requested rather than once it connects, so a host's
                    // Join button hides while we are still joining rather than blinking through an
                    // interval where the app believes we are not in the call we are joining.
                    reportCallStarted(callData)
                    // At the request, not the grant: a refused microphone is an outcome of this attempt (044 R10).
                    reporter = ElementCallAnalyticsReporter(analytics, platform::elapsedRealtimeMs, undecidedCallKind = { callKindOf(callData.isDm) })
                }
            }
        }
    }

    private fun reportCallStarted(callData: ElementCallData) {
        hasReportedCallStarted = true
        lifecycleListener.onCallStarted(callData)
    }

    /** The outputs, listed and routed, into the snapshot until the attempt ends (045 C10). */
    private fun startAudioDevices(preferLoudspeaker: Boolean) {
        if (hasStartedAudioDevices) return
        hasStartedAudioDevices = true
        audioDeviceController.start(preferLoudspeaker = preferLoudspeaker)
        // Observed rather than echoed back from selectAudioDevice, because the route also changes
        // without us asking: a headset is unplugged, or the platform overrides the choice.
        audioDevicesJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            launch(start = CoroutineStart.UNDISPATCHED) {
                audioDeviceController.devices.collect { value -> updateState { it.copy(audioDevices = value.toImmutableList()) } }
            }
            audioDeviceController.selectedDevice.collect { value -> updateState { it.copy(selectedAudioDevice = value) } }
        }
    }

    /**
     * The lobby's room, from open to Join (045 R59, R60, C7, C22, C23): opened in the stored format, and opened again,
     * back in loading, whenever the format changes. The previous load is cancelled and waited for first, so its room is
     * shut before the next one opens.
     */
    private suspend fun followLobby(callData: ElementCallData) {
        storedPreferences.reload()
        storedPreferences.value
            .map { it.membershipFormat }
            .distinctUntilChanged()
            .collectLatest { format -> loadLobby(callData, format) }
    }

    private suspend fun loadLobby(callData: ElementCallData, format: MatrixRtcMembershipFormat) = coroutineScope {
        val previous = mutex.withLock {
            if (_state.value?.connection !is ElementCallConnection.Lobby) return@coroutineScope
            // Back to loading: the count cleared, the intent settled again, the label gone (C23).
            updateState {
                it.copy(
                    connection = ElementCallConnection.Lobby(memberCount = null, hasSeenMembers = false),
                    settledIntent = null,
                    joiningFormat = format,
                )
            }
            rtcRoom.also { rtcRoom = null }
        }
        syncCamera()
        previous?.shutdown()
        Timber.i("ElementCall: the lobby is reading ${callData.roomId} in $format")
        val lobbyScope = this
        val counts = Channel<Int>(Channel.CONFLATED)
        // One timeout over the three reads, so an intent or a first count that never answers is bounded too (C22).
        val loaded = withTimeoutOrNull(LOBBY_LOADING_TIMEOUT) {
            runCatchingExceptions {
                val room = openLobbyRoom(callData, format)
                val intent = room.callIntent(requested = callData.intent).getOrThrow()
                lobbyScope.launch {
                    room.memberCountUpdates().collect { counts.send(it) }
                    counts.close()
                }
                intent to counts.receive()
            }
        }
        val (intent, count) = when {
            loaded == null -> return@coroutineScope failLobby("the call was not read within $LOBBY_LOADING_TIMEOUT")
            loaded.isFailure -> return@coroutineScope failLobby(loaded.exceptionOrNull()?.message ?: "unknown")
            else -> loaded.getOrThrow()
        }
        Timber.i("ElementCall: the lobby has read the call: $intent, $count in it")
        val isVideo = intent == MatrixRtcCallIntent.VIDEO
        // Before the snapshot, so the output appears with the label, the count and the camera (R54, R59).
        audioDeviceController.setPreferLoudspeaker(isVideo)
        mutex.withLock {
            val current = _state.value ?: return@withLock
            if (current.connection !is ElementCallConnection.Lobby) return@withLock
            // A choice made before a format change stands; after a refused microphone the camera is off (R26).
            val joinsWithCamera = if (isCameraChosenByUser) {
                current.isCameraEnabled
            } else {
                isVideo && !current.isMicrophonePermissionRefused &&
                !current.isCameraPermissionRefused
            }
            this@DefaultElementCallController.joinsWithCamera = joinsWithCamera
            _state.value = current.copy(
                connection = ElementCallConnection.Lobby(memberCount = count, hasSeenMembers = count > 0),
                settledIntent = intent,
                isCameraEnabled = joinsWithCamera,
                audioDevices = audioDeviceController.devices.value.toImmutableList(),
                selectedAudioDevice = audioDeviceController.selectedDevice.value,
            )
        }
        syncCamera()
        // Live until Join (R42). A subscription that stops keeps the last count (R60).
        for (next in counts) {
            updateState {
                val lobby = it.connection as? ElementCallConnection.Lobby ?: return@updateState it
                it.copy(connection = ElementCallConnection.Lobby(memberCount = next, hasSeenMembers = lobby.hasSeenMembers || next > 0))
            }
        }
    }

    /** Opened whatever happens to this coroutine, and shut again if the lobby no longer wants it: a dropped open would hold the room (C23). */
    private suspend fun openLobbyRoom(callData: ElementCallData, format: MatrixRtcMembershipFormat): MatrixRtcRoom {
        val room = withContext(NonCancellable) {
            val room = rtcClient.room(callData.roomId, format).getOrThrow()
            val isKept = mutex.withLock {
                (_state.value?.connection is ElementCallConnection.Lobby).also { if (it) rtcRoom = room }
            }
            if (!isKept) room.shutdown()
            room.takeIf { isKept }
        }
        currentCoroutineContext().ensureActive()
        return room ?: error("the lobby closed while its room opened")
    }

    /** The call could not be read: the failure screen, as a join that cannot open the room (045 R60, C16). */
    private suspend fun failLobby(reason: String) {
        mutex.withLock {
            if (_state.value?.connection !is ElementCallConnection.Lobby) return
            val attempt = ElementCallAnalyticsReporter(analytics, platform::elapsedRealtimeMs, undecidedCallKind = { callKindOf(isDm = false) })
            reporter = attempt
            attempt.attempted(callKindOf(isDm = false))
            fail(attempt, FailureReport.ROOM_NOT_OPENED, ElementCallFailure.Other("Room not opened: $reason", isJoinFailure = true))
            // Nothing left to hear or see: the audio goes back now, not at Close (C27).
            releaseLobbyAudio()
        }
    }

    /** Give back the focus and the outputs the lobby took, if it did (045 R37). */
    private fun releaseLobbyAudio() {
        if (hasRequestedAudioFocus) {
            audioFocus.releaseAudioFocus()
            hasRequestedAudioFocus = false
            holdsAudioFocus = false
        }
        if (hasStartedAudioDevices) {
            audioDevicesJob?.cancel()
            audioDevicesJob = null
            audioDeviceController.stop()
            hasStartedAudioDevices = false
        }
    }

    /** In the lobby the audio is held exactly while the screen is visible (045 C27). Under [mutex]. */
    private fun syncLobbyAudioFocus() {
        if (_state.value?.connection !is ElementCallConnection.Lobby) return
        if (isCallScreenVisible && !hasRequestedAudioFocus) {
            hasRequestedAudioFocus = true
            holdsAudioFocus = audioFocus.requestAudioFocus(
                onFocusLost = { onLobbyFocusChanged(held = false) },
                onFocusGained = { onLobbyFocusChanged(held = true) },
            )
            Timber.i("ElementCall: the lobby ${if (holdsAudioFocus) "holds" else "waits for"} the audio")
        } else if (!isCallScreenVisible && hasRequestedAudioFocus) {
            audioFocus.releaseAudioFocus()
            hasRequestedAudioFocus = false
            holdsAudioFocus = false
            Timber.i("ElementCall: the lobby gave the audio back for the background")
        }
    }

    /** A call taking the audio stops the lobby's camera; getting it back restarts it as the user left it (045 R48). */
    private fun onLobbyFocusChanged(held: Boolean) {
        scope.launch {
            mutex.withLock {
                if (!hasRequestedAudioFocus) return@launch
                holdsAudioFocus = held
            }
            Timber.i("ElementCall: the lobby ${if (held) "has the audio back" else "lost the audio"}")
            syncCamera()
        }
    }

    /**
     * Start or stop the camera before media: in the lobby, exactly while it is on, allowed, loaded, visible and holding the
     * audio (045 R31, R48, C15); after its Join, kept running only while on and allowed, so the call takes it over (R49).
     * Once media is up the media session drives it. Without a lobby the media session starts it, as before.
     */
    private suspend fun syncCamera() = cameraMutex.withLock {
        val camera = camera ?: return@withLock
        if (call != null) return@withLock
        val current = _state.value ?: return@withLock
        val lobby = current.connection as? ElementCallConnection.Lobby
        val isAllowed = current.isCameraEnabled && current.isCameraPermissionGranted
        val runs = when {
            lobby != null -> isAllowed && !lobby.isLoading && isCallScreenVisible && holdsAudioFocus
            isFromLobby -> isAllowed && camera.isCapturing.value
            else -> return@withLock
        }
        if (runs && !camera.isCapturing.value) {
            Timber.i("ElementCall: starting the lobby's camera")
            camera.start()
        } else if (!runs && camera.isCapturing.value) {
            Timber.i("ElementCall: stopping the camera before media")
            camera.stop()
        }
    }

    override fun join() {
        scope.launch {
            mutex.withLock {
                val current = _state.value ?: return@launch
                val lobby = current.connection as? ElementCallConnection.Lobby ?: return@launch
                val label = lobby.joinLabel ?: return@launch
                if (!current.isMicrophonePermissionGranted) {
                    Timber.i("ElementCall: Join refused, the microphone is not allowed")
                    return@launch
                }
                // The button at the tap, never the host's flag: only Start notifies (R21, R57, C9).
                val callData = current.callData.copy(isStartingCall = label == ElementCallLobbyJoinLabel.START)
                Timber.i("ElementCall: $label from the lobby")
                lobbyJob?.cancel()
                lobbyJob = null
                _state.value = current.copy(callData = callData, connection = ElementCallConnection.Joining)
                reportCallStarted(callData)
                val attempt = ElementCallAnalyticsReporter(analytics, platform::elapsedRealtimeMs, undecidedCallKind = { callKindOf(callData.isDm) })
                reporter = attempt
                hasStartedForegroundService = true
                platform.startForegroundService()
                // The lobby's focus is kept, never released and taken again (C27); taken here only if it had none.
                if (!hasRequestedAudioFocus) {
                    hasRequestedAudioFocus = true
                    audioFocus.requestAudioFocus {}
                }
                callJob = scope.launch {
                    launch { followInterruptions() }
                    runCall(callData, attempt)
                }
            }
        }
    }

    override fun setCallScreenVisible(visible: Boolean) {
        scope.launch {
            mutex.withLock {
                if (isCallScreenVisible == visible) return@launch
                isCallScreenVisible = visible
                // The audio before the camera, which waits for it (C27).
                syncLobbyAudioFocus()
            }
            syncCamera()
        }
    }

    /**
     * What kind of call this join is (feature-hq 030 C1): a DM call, which rings only when the user chose to
     * call, or a group call, which only ever shows the others a notification, and only when we start it.
     * The DM flag is the host's, given when the call opened (045 R40); [intent] is the settled one (C6).
     */
    private fun callKindFor(callData: ElementCallData, intent: MatrixRtcCallIntent): MatrixRtcCallKind = when {
        callData.isDm && callData.isStartingCall -> MatrixRtcCallKind.Direct(MatrixRtcRing(intent, options.ringLifetime?.inWholeMilliseconds?.toULong()))
        callData.isDm -> MatrixRtcCallKind.Direct(ring = null)
        callData.isStartingCall -> MatrixRtcCallKind.Group(MatrixRtcGroupNotify(intent))
        else -> MatrixRtcCallKind.Group(notify = null)
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
                    roomMembers = context.members.toImmutableMap(),
                )
            }
        }
    }

    /**
     * Answer the microphone permission request. Without a lobby, denial fails the call with
     * [ElementCallFailure.MicrophoneRefused] before anything reaches the room (042 R1, R5): it is audio first, and
     * `AudioRecord` would fail anyway. In the lobby the answer is only recorded: a refusal disables Join (045 R27).
     */
    override fun setMicrophonePermissionGranted(granted: Boolean) {
        scope.launch {
            mutex.withLock {
                val current = _state.value ?: return@withLock
                if (current.connection is ElementCallConnection.Lobby) {
                    Timber.i("ElementCall: microphone ${if (granted) "granted" else "refused"} in the lobby")
                    // After a refused microphone the camera is off, and a tap on it asks (R26).
                    updateState {
                        it.copy(
                            isMicrophonePermissionGranted = granted,
                            isMicrophonePermissionRefused = !granted,
                            isCameraEnabled = it.isCameraEnabled && granted,
                        )
                    }
                    if (!granted) joinsWithCamera = false
                    return@withLock
                }
                if (!granted) {
                    Timber.i("ElementCall: microphone permission denied")
                    updateState { it.copy(connection = ElementCallConnection.Failed(ElementCallFailure.MicrophoneRefused)) }
                    reporter?.permissionRefused()
                    return@withLock
                }
                // The host re-reports a permission it already has whenever it is recreated, so this
                // has to be idempotent or a configuration change would join the call a second time.
                if (current.isMicrophonePermissionGranted) return@withLock
                updateState { it.copy(isMicrophonePermissionGranted = true, isMicrophonePermissionRefused = false) }

                // Both must be in place before capture starts: the service so the microphone survives
                // backgrounding, the audio focus so other apps duck.
                hasStartedForegroundService = true
                platform.startForegroundService()
                hasRequestedAudioFocus = true
                audioFocus.requestAudioFocus {}

                // Read under the lock: endCall clears the field, and the launched body may run after it has.
                val attempt = checkNotNull(reporter)
                callJob = scope.launch {
                    // From the start, not the join: a join made during an outage is when the user first needs it (034 C4).
                    launch { followInterruptions() }
                    runCall(current.callData, attempt)
                }
            }
        }
    }

    override fun setCameraPermissionGranted(granted: Boolean) {
        scope.launch {
            val isUpFrontAnswer = isUpFrontCameraAnswerPending
            isUpFrontCameraAnswerPending = false
            updateState { it.copy(isCameraPermissionGranted = granted, isCameraPermissionRefused = !granted) }
            if (!granted) {
                // Off from the refusal, not from when media connects (042 R10); a running call's own flow already says off.
                if (call == null) {
                    joinsWithCamera = false
                    updateState { it.copy(isCameraEnabled = false) }
                    syncCamera()
                }
                return@launch
            }
            // Only recorded in the lobby: the service would post an ongoing call that does not exist (045 R46), and the tap
            // that asked turns the camera on itself.
            if (_state.value?.connection is ElementCallConnection.Lobby) {
                syncCamera()
                return@launch
            }
            // Started again, not for the first time: the service is already running for the
            // microphone, and this is what lets it add the camera type it could not claim before.
            // Without it, capture stops the moment the call is backgrounded.
            hasStartedForegroundService = true
            platform.startForegroundService()
            // Turned on as soon as it is granted: once a call is running, the only way to reach this
            // is having just tapped the camera button, and making the user tap it again would be an
            // odd reward for saying yes.
            //
            // Only once one is running, though. Before that a grant is not an ask - a host may answer
            // this up front, as the screen does for a video call, and on an audio call that would
            // start a camera nobody reached for - so what the call joins with is recorded by the
            // button, in setCameraEnabled, and nowhere else. The up-front answer can also land once
            // the call is running, and then it starts the camera only if the call joins with it.
            if (isUpFrontAnswer && !joinsWithCamera) return@launch
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

    override fun reportConnectivity(isOnline: Boolean) = rtcClient.reportConnectivity(isOnline)

    override fun clearHandRequestFailure() {
        updateState { it.copy(handRequestFailure = null) }
    }

    override val reactionCatalogue: List<MatrixRtcReactionKind> by lazy { platform.reactionCatalogue() }

    // Nothing to tell the user either way: the core logs a refusal, and ours shows only once it has landed (009 R12, R23).
    override fun sendReaction(kind: MatrixRtcReactionKind) {
        val rtcCall = rtcCall ?: return
        scope.launch { rtcCall.sendReaction(kind) }
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
                joinsWithCamera = enabled
                isCameraChosenByUser = true
                updateState { it.copy(isCameraEnabled = enabled) }
                syncCamera()
            } else {
                call.setCameraEnabled(enabled)
            }
        }
    }

    override fun switchCamera() {
        scope.launch {
            val call = call
            if (call != null) call.switchCamera() else camera?.switchCamera()
        }
    }

    override fun setAudioTestToneEnabled(enabled: Boolean) {
        call?.setAudioTestToneEnabled(enabled)
    }

    override fun selectAudioDevice(device: CallAudioDevice) {
        audioDeviceController.select(device)
    }

    override fun setMaximized(maximized: Boolean) {
        // The lobby never minimises: not to the host's bar, not to Picture in Picture (045 R20, R33).
        updateState { if (it.connection is ElementCallConnection.Lobby) it else it.copy(isMaximized = maximized) }
    }

    override fun toggleTileStats() {
        if (!options.isDeveloperModeEnabled) return
        updateState { it.copy(isTileStatsVisible = !it.isTileStatsVisible) }
    }

    override fun setShowsReactions(shows: Boolean) = storedPreferences.setShowsReactions(shows)

    override fun setPlaysReactionSounds(plays: Boolean) = storedPreferences.setPlaysReactionSounds(plays)

    override fun setMembershipFormat(format: MatrixRtcMembershipFormat) = storedPreferences.setMembershipFormat(format)

    override fun setVideoCodec(codec: MatrixRtcVideoCodec) = storedPreferences.setVideoCodec(codec)

    override val videoCodecs: List<MatrixRtcVideoCodec>
        get() = listOf(MatrixRtcVideoCodec.AUTO) + rtcClient.supportedVideoCodecs()

    override fun setSoundEffectsVolume(volume: Float) {
        soundEffectRenderer?.setVolume(storedPreferences.setSoundEffectsVolume(volume))
    }

    override fun playSoundEffectsVolumeSample() {
        Timber.i("ElementCall: sound effects volume ${(preferences.value.soundEffectsVolume * 100).roundToInt()} %")
        soundEffectRenderer?.playReaction(GENERIC_REACTION_SOUND)
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

    private suspend fun runCall(callData: ElementCallData, attempt: ElementCallAnalyticsReporter) {
        updateState { it.copy(connection = ElementCallConnection.Joining) }
        Timber.i("ElementCall: joining ${callData.roomId}")

        // Read once, here, rather than observed: the format is fixed for the lifetime of a room -
        // it decides the member id, the SFU identity and the token endpoint as well as the wire
        // format - so changing it mid-call is not something the core could act on (043 R14).
        // The codec is read with it, so that both take effect from the next join and no earlier (043 C1).
        // Reloaded rather than read on the side, so a format the host wrote since is also what the sheets show.
        val joinPreferences = storedPreferences.reload()
        val storedVideoCodec = joinPreferences.videoCodec
        // The lobby's room, opened in the format it shows, is the one joined (045 C13, C23).
        val lobbyRoom = rtcRoom
        val membershipFormat = _state.value?.joiningFormat?.takeIf { lobbyRoom != null } ?: joinPreferences.membershipFormat
        Timber.i("ElementCall: joining with membership format $membershipFormat, video codec $storedVideoCodec if this device encodes it")
        updateState { it.copy(joiningFormat = membershipFormat) }

        val room = lobbyRoom ?: rtcClient.room(callData.roomId, membershipFormat).getOrElse {
            fail(attempt, FailureReport.ROOM_NOT_OPENED, ElementCallFailure.Other("Join failed: ${it.message}", isJoinFailure = true))
            return
        }
        rtcRoom = room
        // Settled once per attempt: in the lobby while it loaded, otherwise here, before anything is joined (045 C6).
        val intent = _state.value?.settledIntent ?: room.callIntent(requested = callData.intent).getOrElse {
            fail(attempt, FailureReport.ROOM_NOT_OPENED, ElementCallFailure.Other("Join failed: the call's intent: ${it.message}", isJoinFailure = true))
            return
        }
        if (lobbyRoom == null) adoptSettledIntent(intent)
        val kind = callKindFor(callData, intent)
        // The join's own decision, before anything is joined (044 R6, R25, C10).
        attempt.attempted(callKindOf(isDm = kind is MatrixRtcCallKind.Direct))
        // A DM call we start shows that it is calling from here, before the join returns (030 C3).
        if (kind is MatrixRtcCallKind.Direct && kind.ring != null) {
            updateState { it.copy(connection = ElementCallConnection.Calling(callee = null, isRinging = false)) }
        }
        val joined = room.joinCall(kind = kind, intent = intent).getOrElse {
            val report = FailureReport.joinFailed(it)
            when (it) {
                is MatrixRtcJoinException.TimedOut -> fail(attempt, report, ElementCallFailure.JoinTimedOut, "Join failed: ${it.message}")
                is MatrixRtcJoinException.Refused -> fail(attempt, report, ElementCallFailure.JoinRefused(it.errcode), "Join failed: ${it.message}")
                else -> fail(attempt, report, ElementCallFailure.Other("Join failed: ${it.message}", isJoinFailure = true))
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
        soundEffectRenderer = platform.createCallSoundEffectRenderer(preferences.value.soundEffectsVolume)
        // A video call is looked at, not held to an ear, so it starts on the loudspeaker; a headset still wins over both
        // built-in outputs when one is connected. Video as settled, never the camera's state (045 R54). The lobby has
        // already started the outputs and the user may have picked one.
        startAudioDevices(preferLoudspeaker = intent == MatrixRtcCallIntent.VIDEO)
        // Communication mode is device-wide state we borrow, held for as long as the call runs and handed back in endCall().
        // Taken here and not in the lobby, which Android resets after a few seconds with no audio (045 C27); the route
        // is applied again after it.
        audioDeviceController.startCommunication()

        coroutineScope {
            val joinObservers = startJoinObservers(joined, attempt)
            connectMediaAndObserve(joined, storedVideoCodec, attempt)?.let { (failure, report) ->
                joinObservers.cancel()
                fail(attempt, report, failure)
            }
        }
    }

    /** What the call reports from the join on, media or not: its status. */
    private fun CoroutineScope.startJoinObservers(rtcCall: MatrixRtcCall, attempt: ElementCallAnalyticsReporter) = launch(start = CoroutineStart.UNDISPATCHED) {
        observeStatus(rtcCall, attempt)
    }

    /**
     * Without a lobby the camera starts off and follows the intent once the core has settled it, unless the user chose
     * meanwhile (045 C24). The screen asks for the camera up front only then, on a video call.
     */
    private suspend fun adoptSettledIntent(intent: MatrixRtcCallIntent) {
        mutex.withLock {
            val current = _state.value ?: return
            val isVideo = intent == MatrixRtcCallIntent.VIDEO
            // A refusal already in is the user's answer too (042 R10).
            val camera = if (isCameraChosenByUser) current.isCameraEnabled else isVideo && !current.isCameraPermissionRefused
            joinsWithCamera = camera
            isUpFrontCameraAnswerPending = camera && !current.isCameraPermissionGranted
            Timber.i("ElementCall: the call is ${if (isVideo) "video" else "voice"}, the camera ${if (camera) "on" else "off"}")
            _state.value = current.copy(settledIntent = intent, isCameraEnabled = camera)
        }
    }

    /** Media, the microphone, the routing and the observers. @return what failed, with what it is for analytics, or null. */
    private suspend fun CoroutineScope.connectMediaAndObserve(
        joined: MatrixRtcCall,
        storedVideoCodec: MatrixRtcVideoCodec,
        attempt: ElementCallAnalyticsReporter,
    ): Pair<ElementCallFailure.Other, FailureReport>? {
        // Asked here rather than at the start of the join: the room is open, so the core is loaded. A stored codec the
        // device cannot encode, from a restored backup or another device, publishes as Auto and stays stored (043 C2).
        val videoCodec = storedVideoCodec.takeIf { it in videoCodecs } ?: MatrixRtcVideoCodec.AUTO
        val camera = checkNotNull(camera) { "no camera for this attempt" }
        val connected = joined.connectMedia(camera = camera, isSfuFallbackEnabled = options.isSfuFallbackEnabled, videoCodec = videoCodec).getOrElse {
            return ElementCallFailure.Other("Media failed: ${it.message}", isJoinFailure = true) to FailureReport.MEDIA_NOT_ATTACHED
        }
        // Under the camera's lock: from here the media session drives the camera, and a lobby start in flight finishes first.
        cameraMutex.withLock { call = connected }
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
            // The core may be ending the call under us: once it gives our own media server up, a
            // publish fails at once, while the end and its reason come a moment later (034 R26). That
            // reason is the one to show, and the status observer shows it, so wait for it briefly.
            val ended = withTimeoutOrNull(PUBLISH_FAILURE_GRACE) { joined.status.first { status -> status is MatrixRtcCallStatus.Ended } }
            if (ended != null) {
                Timber.i("ElementCall: publishing the microphone failed as the call ended: ${it.message}")
                return null
            }
            // Still a join failure for analytics: connected waits for the publish (044 R8, C6).
            return ElementCallFailure.Other("Microphone failed: ${it.message}", isJoinFailure = false) to FailureReport.MICROPHONE_NOT_PUBLISHED
        }
        Timber.i("ElementCall: connected as ${connected.localMemberId}")
        attempt.microphonePublished(joined.memberCount.value)

        // Read before the observers start, because they publish the call's own camera state - off,
        // it has not been asked to capture yet - over the state the call is joining with.
        val wantsCamera = _state.value?.isCameraEnabled == true

        startObservers(joined, connected, attempt)

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
    private fun CoroutineScope.observeStatus(rtcCall: MatrixRtcCall, attempt: ElementCallAnalyticsReporter): Job = launch(start = CoroutineStart.UNDISPATCHED) {
        rtcCall.status.collect { status ->
            // From the ring going out until it stops ringing, whatever stops it (030 R23).
            val isRinging = status is MatrixRtcCallStatus.Calling && status.ring is MatrixRtcRingPhase.Ringing
            if (isRinging) soundEffectRenderer?.startLoop(CallSoundEffect.RINGBACK) else soundEffectRenderer?.stopLoop()
            // First, before anything is torn down on an end (044 R13).
            attempt.status(status, participants = rtcCall.memberCount.value)
            when (status) {
                MatrixRtcCallStatus.Connecting -> updateState { it.copy(connection = ElementCallConnection.ConnectingMedia) }
                is MatrixRtcCallStatus.Calling -> updateState {
                    it.copy(connection = ElementCallConnection.Calling(status.callee, isRinging = status.ring is MatrixRtcRingPhase.Ringing))
                }
                is MatrixRtcCallStatus.Connected -> updateState {
                    it.copy(
                        connection = ElementCallConnection.Connected,
                        connectedAtElapsedMs = it.connectedAtElapsedMs ?: platform.elapsedRealtimeAt(status.sinceMs),
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
                        val report = FailureReport.connectionClosed(lost.reason)
                        fail(attempt, report, ElementCallFailure.ConnectionLost(lost.reason), "Connection lost: ${lost.message}")
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

    /**
     * Fan the call's flows into the snapshot, for as long as the calling scope lives.
     *
     * Every collector is started [CoroutineStart.UNDISPATCHED], so it runs synchronously up to its
     * `collect` and is subscribed by the time this returns. That is the whole point: a dispatched
     * launch would only be *queued* here, leaving the caller free to announce a connected call to a
     * screen while nothing was yet listening to the events that call raises.
     */
    @OptIn(FlowPreview::class)
    private fun CoroutineScope.startObservers(rtcCall: MatrixRtcCall, call: MatrixRtcMediaSession, attempt: ElementCallAnalyticsReporter) {
        fun observe(block: suspend () -> Unit) = launch(start = CoroutineStart.UNDISPATCHED) { block() }

        observe { rtcCall.memberCount.collect { value -> updateState { it.copy(memberCount = value) } } }
        observe { call.tiles.collect { value -> updateState { it.copy(roster = value) } } }
        observe { call.interruptions.collect { interruptionRecord.value = it } }
        keySpells.clear()
        observe {
            // From both the tiles and the record: a change reaches the tiles first (C5), and a spell has to have begun by
            // then or the tile would draw its spinner for the moment in between.
            combine(call.tiles, call.interruptions) { roster, record ->
                record.unableToDecrypt.toSet() + roster.detail.values.filter { it.unableToDecrypt != null }.map { it.id.memberId }
            }
                .distinctUntilChanged()
                .collect { reconcileKeySpells(it) }
        }
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
        observe {
            call.events.collect { event ->
                Timber.d("ElementCall: $event")
                attempt.callEvent(event)
                when (event) {
                    is MatrixRtcCallEvent.FrameEncryption -> updateState {
                        it.copy(frameEncryption = (it.frameEncryption + (event.memberId to event.state)).toImmutableMap())
                    }
                    is MatrixRtcCallEvent.ParticipantJoined -> playJoinLeaveSound(CallSoundEffect.JOIN, event.memberId, event.participantCount)
                    is MatrixRtcCallEvent.ParticipantLeft -> playJoinLeaveSound(CallSoundEffect.LEAVE, event.memberId, event.participantCount)
                    is MatrixRtcCallEvent.HandRaised -> playRaisedHandSound(event.memberId)
                    is MatrixRtcCallEvent.Reaction -> this@startObservers.receiveReaction(event, call.localMemberId)
                    else -> Unit
                }
            }
        }
    }

    /**
     * Which reactions arrive at all is the core's: only while we are in the call, never a replay, never a member's second
     * inside three seconds. What is left here is the user's preference, the spread, and the sound with its bubble.
     */
    private fun CoroutineScope.receiveReaction(event: MatrixRtcCallEvent.Reaction, localMemberId: String) {
        // Read on arrival: a change applies to the next reaction and leaves those already scheduled alone (037 R15).
        // Hidden reactions play no sound, whatever the sound switch holds (043 R7).
        val shows = preferences.value.showsReactions
        val playsSound = shows && preferences.value.playsReactionSounds
        Timber.i("ElementCall: ${event.memberId} reacted ${event.name.ifEmpty { "(no name)" }}: shown $shows, sound $playsSound")
        if (!shows) return
        val snapshot = _state.value ?: return
        val userId = snapshot.userIdOf(event.memberId, localMemberId) ?: run {
            Timber.w("ElementCall: reaction from ${event.memberId}, who is not on the roster")
            return
        }
        val spread = platform.reactionSpread().coerceIn(Duration.ZERO, MAXIMUM_REACTION_SPREAD)
        val reaction = MatrixRtcReaction(
            id = ++nextReactionId,
            memberId = event.memberId,
            userId = userId,
            emoji = event.emoji,
            spread = spread,
        )
        launch {
            delay(spread)
            // With its bubble, not on arrival (037 R2b).
            if (playsSound && soundEffectRenderer?.playReaction(event.sound) == false) {
                Timber.i("ElementCall: reaction sound ${event.sound} not played, eight already are")
            }
            _reactions.emit(reaction)
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

    /** [report] is required, so no failure reaches analytics as `other` by default (044 C7). */
    private fun fail(attempt: ElementCallAnalyticsReporter, report: FailureReport, failure: ElementCallFailure, message: String = failure.logText()) {
        Timber.w("ElementCall: $message")
        _state.update { it?.copy(connection = ElementCallConnection.Failed(failure)) }
        attempt.failed(report)
        // The failure screen draws no camera: its light goes off now, not at Close (045 C4).
        camera?.stop()
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
        val endingCamera: MatrixRtcLocalCamera?
        val wasCallStartedReported: Boolean
        val heldAudioFocus: Boolean
        val startedAudioDevices: Boolean
        val startedForegroundService: Boolean
        mutex.withLock {
            if (_state.value == null && callJob == null) return
            Timber.i("ElementCall: ending call (leave=$leave)")
            // First: nothing below may sound like people leaving.
            joinLeaveSoundPolicy?.silence()
            raisedHandSoundPolicy?.silence()
            endedCallData = _state.value?.callData
            // Before any teardown (044 R13, C3). An end the core already reported has made these no-ops; one it has set but
            // the status observer has not reached yet is still the core's.
            reporter?.callOver(coreEnd = (rtcCall?.status?.value as? MatrixRtcCallStatus.Ended)?.reason)
            reporter = null
            callJob?.cancel()
            callJob = null
            roomJob?.cancel()
            roomJob = null
            lobbyJob?.cancel()
            lobbyJob = null
            attemptJob?.cancel()
            attemptJob = null
            audioDevicesJob?.cancel()
            audioDevicesJob = null
            endingCamera = camera
            camera = null
            attemptCamera.value = null
            isFromLobby = false
            wasCallStartedReported = hasReportedCallStarted
            hasReportedCallStarted = false
            heldAudioFocus = hasRequestedAudioFocus
            hasRequestedAudioFocus = false
            holdsAudioFocus = false
            startedAudioDevices = hasStartedAudioDevices
            hasStartedAudioDevices = false
            startedForegroundService = hasStartedForegroundService
            hasStartedForegroundService = false
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

        // Not left to the GC: a camera torn down from recents would otherwise stay open, light on, until the process died.
        endingCamera?.stop()
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
        // Only what this attempt took: a lobby closed before Join started no service and told the host nothing (045 R46).
        if (!isNextCallRunning) {
            if (startedAudioDevices) audioDeviceController.stop()
            if (heldAudioFocus) audioFocus.releaseAudioFocus()
            if (startedForegroundService) platform.stopForegroundService()
        }
        // Clears the host's active call, and stops a ring that never got answered - hanging up from
        // the call screen while the far end is still ringing us is a real sequence, not a hypothetical.
        if (wasCallStartedReported) lifecycleListener.onCallEnded(endedCallData, coreEnd?.toEndReason() ?: ElementCallEndReason.LEFT)
    }

    internal companion object {
        const val HANGUP_REASON = "m.hangup"

        /** How long a cause has to last before the interruptions list shows it, as on iOS. */
        val INTERRUPTION_HOLD = 2500.milliseconds

        /**
         * How long a failed microphone publish waits for the core to end the call with its own reason before failing
         * the call as a microphone failure, as on iOS. The core reports within a few task hops.
         */
        val PUBLISH_FAILURE_GRACE = 2.seconds

        /**
         * How long a tile shows it is waiting for a member's key before saying the key is missing. Counted from the core's
         * report, which already waited out a key rotation (its key grace, C6). Nothing here can tell why a key does not come,
         * so past this a tile says only that it is missing, for that one member (034 Q8).
         */
        val KEYS_MISSING_AFTER = 10.seconds

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
         * One bound over the lobby's three reads, the room seeded, the intent and the first count (feature-hq 045 C22): the
         * seeding timeout's own value, so a stalled seed hits both at once.
         */
        val LOBBY_LOADING_TIMEOUT = 30.seconds
    }
}

private data class VideoStreamKey(val memberId: String, val kind: MatrixRtcStreamKind)

private fun MatrixRtcCallEnd.endSound(): CallSoundEffect? = when (this) {
    MatrixRtcCallEnd.Declined -> CallSoundEffect.DECLINED
    MatrixRtcCallEnd.Unanswered -> CallSoundEffect.TIMEOUT
    else -> null
}

private fun ElementCallSnapshot.userIdOf(memberId: String, localMemberId: String): UserId? =
    ownTile?.takeIf { memberId == localMemberId }?.userId
        ?: roster.order.firstOrNull { it.id.memberId == memberId }?.userId

/** The core's wall-clock moment on the monotonic clock durations are counted on. */
private fun ElementCallPlatform.elapsedRealtimeAt(epochMs: Long): Long {
    val ago = (currentTimeMs() - epochMs).coerceAtLeast(0)
    return elapsedRealtimeMs() - ago
}

private fun ElementCallFailure.logText(): String = (this as? ElementCallFailure.Other)?.message ?: toString()
