/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.test

import io.element.android.call.api.rtc.MatrixRtcAudioLevel
import io.element.android.call.api.rtc.MatrixRtcCall
import io.element.android.call.api.rtc.MatrixRtcCallEnd
import io.element.android.call.api.rtc.MatrixRtcCallEvent
import io.element.android.call.api.rtc.MatrixRtcCallIntent
import io.element.android.call.api.rtc.MatrixRtcCallKind
import io.element.android.call.api.rtc.MatrixRtcCallStatus
import io.element.android.call.api.rtc.MatrixRtcClient
import io.element.android.call.api.rtc.MatrixRtcDetailWindow
import io.element.android.call.api.rtc.MatrixRtcInterruptions
import io.element.android.call.api.rtc.MatrixRtcLeaveReason
import io.element.android.call.api.rtc.MatrixRtcLocalCamera
import io.element.android.call.api.rtc.MatrixRtcLocalState
import io.element.android.call.api.rtc.MatrixRtcMediaSession
import io.element.android.call.api.rtc.MatrixRtcMembership
import io.element.android.call.api.rtc.MatrixRtcMembershipFormat
import io.element.android.call.api.rtc.MatrixRtcReactionKind
import io.element.android.call.api.rtc.MatrixRtcReceiveStats
import io.element.android.call.api.rtc.MatrixRtcRoom
import io.element.android.call.api.rtc.MatrixRtcScreenCaptureToken
import io.element.android.call.api.rtc.MatrixRtcSignallingHealth
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcStreamRef
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.MatrixRtcTileRoster
import io.element.android.call.api.rtc.MatrixRtcVideoCodec
import io.element.android.call.api.rtc.MatrixRtcVideoConstraints
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.api.rtc.windowed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

class FakeMatrixRtcClient(
    private val roomResult: () -> Result<MatrixRtcRoom>? = { null },
    private val joinResult: () -> Result<MatrixRtcCall>? = { null },
    /** Completed by a test to let a join return; a join waits on it while it is pending. */
    private val joinGate: CompletableDeferred<Unit>? = null,
    /** What the members of a call here agree on, as the core reads them; null when they do not, and video then applies. */
    private val membersIntent: MatrixRtcCallIntent? = null,
    /** Completed by a test to let a room open; an open waits on it while it is pending, as the core seeding it. */
    private val roomGate: CompletableDeferred<Unit>? = null,
    /** Called on each room as it opens, before anyone has read it: what a test scripts the lobby's reads with. */
    private val onRoomOpened: (FakeMatrixRtcRoom) -> Unit = {},
    /** What our camera shows while it runs: a harness gives it a pattern; none for a test, which counts. */
    private val cameraFrames: () -> Flow<MatrixRtcVideoFrame> = { emptyFlow() },
    /** What [supportedVideoCodecs] answers: the codecs "this device" can encode. */
    var videoCodecs: List<MatrixRtcVideoCodec> = listOf(MatrixRtcVideoCodec.VP8, MatrixRtcVideoCodec.H264, MatrixRtcVideoCodec.VP9),
) : MatrixRtcClient {
    var roomCount = 0
        private set
    var lastRoom: FakeMatrixRtcRoom? = null
        private set
    var lastMembershipFormat: MatrixRtcMembershipFormat? = null
        private set

    /** The call joined in the last room. */
    val lastCall: FakeMatrixRtcCall? get() = lastRoom?.lastCall

    /** Every room opened, in order. */
    val rooms = mutableListOf<FakeMatrixRtcRoom>()

    /** Every camera handed out, one per call attempt. */
    val cameras = mutableListOf<FakeMatrixRtcLocalCamera>()

    /** What [camera] hands out; a harness gives it pictures. */
    var newCamera: () -> FakeMatrixRtcLocalCamera = { FakeMatrixRtcLocalCamera(pictures = cameraFrames()) }

    override fun camera(): MatrixRtcLocalCamera = newCamera().also { cameras += it }

    /** What [signallingHealth] reports; set it to move the homeserver in and out of reach. */
    val signalling = MutableStateFlow<MatrixRtcSignallingHealth>(MatrixRtcSignallingHealth.Reachable)

    /** Every [reportConnectivity], in order. */
    val connectivityReports = mutableListOf<Boolean>()

    override fun signallingHealth(): Flow<MatrixRtcSignallingHealth> = signalling

    override fun reportConnectivity(isOnline: Boolean) {
        connectivityReports += isOnline
    }

    override fun supportedVideoCodecs(): List<MatrixRtcVideoCodec> = videoCodecs

    override suspend fun room(roomId: RoomId, format: MatrixRtcMembershipFormat): Result<MatrixRtcRoom> {
        roomCount++
        lastMembershipFormat = format
        roomGate?.await()
        roomResult()?.let { return it }
        val room = FakeMatrixRtcRoom(roomId, joinResult, joinGate, membersIntent, format)
        onRoomOpened(room)
        rooms += room
        lastRoom = room
        return Result.success(room)
    }
}

class FakeMatrixRtcRoom(
    override val roomId: RoomId,
    private val joinResult: () -> Result<MatrixRtcCall>? = { null },
    private val joinGate: CompletableDeferred<Unit>? = null,
    /** What the members agree on; null when they do not. */
    private val membersIntent: MatrixRtcCallIntent? = null,
    /** The format it was opened in. */
    val membershipFormat: MatrixRtcMembershipFormat = MatrixRtcMembershipFormat.MSC,
) : MatrixRtcRoom {
    var joinCallCount = 0
        private set
    var lastCall: FakeMatrixRtcCall? = null
        private set
    var lastKind: MatrixRtcCallKind? = null
        private set
    var shutdownCount = 0
        private set

    /** The intent the last join stated. */
    var lastJoinIntent: MatrixRtcCallIntent? = null
        private set

    /** Every `requested` [callIntent] was asked with, in order. */
    val callIntentRequests = mutableListOf<MatrixRtcCallIntent?>()

    /** What [callIntent] answers; by default the core's order: requested, else the members', else video. Suspend in it to hold it. */
    var callIntentResult: suspend (requested: MatrixRtcCallIntent?) -> Result<MatrixRtcCallIntent> = { requested ->
        Result.success(requested ?: membersIntent ?: MatrixRtcCallIntent.VIDEO)
    }

    /** The core's count for the room's call; null before its first snapshot, which holds the lobby in loading. */
    val memberCount = MutableStateFlow<Int?>(0)

    /** What [memberCountUpdates] returns; replace it to end or fail the subscription. */
    var memberCountFlow: Flow<Int> = memberCount.filterNotNull()

    /** How many count subscriptions are open now. */
    var openMemberCountSubscriptions = 0
        private set

    override suspend fun joinCall(kind: MatrixRtcCallKind, intent: MatrixRtcCallIntent, applicationSlotId: String?): Result<MatrixRtcCall> {
        joinCallCount++
        lastKind = kind
        lastJoinIntent = intent
        joinGate?.await()
        joinResult()?.let { return it }
        return Result.success(FakeMatrixRtcCall(roomId, "m.call#${applicationSlotId ?: "room"}", intent).also { lastCall = it })
    }

    override suspend fun callIntent(requested: MatrixRtcCallIntent?, applicationSlotId: String?): Result<MatrixRtcCallIntent> {
        callIntentRequests += requested
        return callIntentResult(requested)
    }

    override fun memberCountUpdates(applicationSlotId: String?): Flow<Int> = memberCountFlow
        .onStart { openMemberCountSubscriptions++ }
        .onCompletion { openMemberCountSubscriptions-- }

    override suspend fun shutdown() {
        shutdownCount++
    }
}

class FakeMatrixRtcCall(
    override val roomId: RoomId,
    override val slotId: String,
    override val intent: MatrixRtcCallIntent = MatrixRtcCallIntent.VIDEO,
) : MatrixRtcCall {
    override val memberId: String = "aLocalMemberId"
    override val members = MutableStateFlow(emptyList<MatrixRtcMembership>())
    override val memberCount = MutableStateFlow(0)

    /**
     * Pushed by a test, as the core would. On its own it follows a plain call: [MatrixRtcCallStatus.Connected] once
     * media connects from [MatrixRtcCallStatus.Connecting], and [MatrixRtcCallEnd.Left] on a leave.
     */
    override val status = MutableStateFlow<MatrixRtcCallStatus>(MatrixRtcCallStatus.Connecting)

    /** The `since` a plain call reports once media connects, in ms since the Unix epoch. */
    var connectedSinceMs: Long = 0L

    var connectMediaCount = 0
        private set
    var leaveCount = 0
        private set
    var lastMediaSession: FakeMatrixRtcMediaSession? = null
        private set

    /** What [raiseHand] answers; suspend in it to hold the request in flight. */
    var raiseHandResult: suspend () -> Result<Unit> = { Result.success(Unit) }

    /** What [lowerHand] answers. */
    var lowerHandResult: suspend () -> Result<Unit> = { Result.success(Unit) }

    var raiseHandCount = 0
        private set
    var lowerHandCount = 0
        private set

    override suspend fun raiseHand(): Result<Unit> {
        raiseHandCount++
        return raiseHandResult()
    }

    override suspend fun lowerHand(): Result<Unit> {
        lowerHandCount++
        return lowerHandResult()
    }

    /** What [sendReaction] answers. */
    var sendReactionResult: suspend (MatrixRtcReactionKind) -> Result<Unit> = { Result.success(Unit) }

    /** Every reaction asked for, in order, refused or not. */
    val sentReactions = mutableListOf<MatrixRtcReactionKind>()

    override suspend fun sendReaction(kind: MatrixRtcReactionKind): Result<Unit> {
        sentReactions += kind
        return sendReactionResult(kind)
    }

    /** Called as media is asked for, before it connects: what a test checks has happened by then. */
    var onConnectMedia: () -> Unit = {}

    /** What [connectMedia] fails with instead of connecting; null connects. */
    var connectMediaFailure: Throwable? = null

    /** The session [connectMedia] connects: a harness gives it pictures and our own tile. */
    var newMediaSession: () -> FakeMatrixRtcMediaSession = { FakeMatrixRtcMediaSession() }

    /** What the last [connectMedia] asked for. */
    var lastIsSfuFallbackEnabled: Boolean? = null
        private set
    var lastVideoCodec: MatrixRtcVideoCodec? = null
        private set

    /** The camera the last [connectMedia] was handed. */
    var lastCamera: MatrixRtcLocalCamera? = null
        private set

    override suspend fun connectMedia(
        camera: MatrixRtcLocalCamera,
        isSfuFallbackEnabled: Boolean,
        videoCodec: MatrixRtcVideoCodec,
    ): Result<MatrixRtcMediaSession> {
        lastCamera = camera
        lastIsSfuFallbackEnabled = isSfuFallbackEnabled
        lastVideoCodec = videoCodec
        connectMediaCount++
        onConnectMedia()
        connectMediaFailure?.let { return Result.failure(it) }
        val session = newMediaSession()
        session.camera = camera as? FakeMatrixRtcLocalCamera
        lastMediaSession = session
        return Result.success(session).also {
            if (status.value == MatrixRtcCallStatus.Connecting) status.value = MatrixRtcCallStatus.Connected(connectedSinceMs, degraded = false)
        }
    }

    override suspend fun leave(reason: MatrixRtcLeaveReason?): Result<Unit> {
        leaveCount++
        if (status.value !is MatrixRtcCallStatus.Ended) status.value = MatrixRtcCallStatus.Ended(MatrixRtcCallEnd.Left)
        return Result.success(Unit)
    }

    override fun close() = Unit
}

class FakeMatrixRtcMediaSession(
    /** Pictures for a harness to draw; null opens streams that never send a frame, which is what a test counts. */
    private val frames: ((memberId: String, kind: MatrixRtcStreamKind) -> Flow<MatrixRtcVideoFrame>)? = null,
) : MatrixRtcMediaSession {
    override val localMemberId: String = "aLocalMemberId"

    /** The camera the call was handed: turning ours on starts it and attaches it, as the real session does (045 R49). */
    var camera: FakeMatrixRtcLocalCamera? = null

    private val _events = MutableSharedFlow<MatrixRtcCallEvent>(extraBufferCapacity = 8)
    override val events: Flow<MatrixRtcCallEvent> = _events

    /**
     * Set directly for a roster with detail for everything, the core's default window; or through
     * [pushRoster] for one the declared window narrows, as the core would.
     */
    override val tiles = MutableStateFlow(MatrixRtcTileRoster.EMPTY)

    override val interruptions = MutableStateFlow(MatrixRtcInterruptions.NONE)

    /** Every window [setDetailWindow] was given, in order. */
    val detailWindows = mutableListOf<MatrixRtcDetailWindow>()

    val detailWindow: MatrixRtcDetailWindow? get() = detailWindows.lastOrNull()

    /** The last roster pushed, before the window narrowed it: what a re-declared window is applied to. */
    private var pushedRoster: MatrixRtcTileRoster? = null

    /** Every threshold [setRankingThreshold] was given, in order. */
    val rankingThresholds = mutableListOf<Int>()

    /** Every tile [pushRoster] has seen, in the order it first appeared: the fake's join order. */
    private val arrivals = LinkedHashSet<MatrixRtcTileId>()

    /**
     * Publish [roster] as the core would: the order whole, detail only inside the declared window.
     * With no window declared, detail for everything. At or below the ranking threshold, the order
     * is heroes first, then the order each tile was first pushed in.
     */
    fun pushRoster(roster: MatrixRtcTileRoster) {
        roster.order.forEach { arrivals += it.id }
        val threshold = rankingThresholds.lastOrNull()
        val ordered = if (threshold != null && roster.order.size <= threshold) {
            val arrival = arrivals.withIndex().associate { it.value to it.index }
            roster.copy(order = roster.order.sortedWith(compareBy({ !it.isHero }, { arrival.getValue(it.id) })))
        } else {
            roster
        }
        pushedRoster = ordered
        tiles.value = detailWindow?.let { ordered.windowed(it) } ?: ordered
    }

    override fun setRankingThreshold(tiles: Int) {
        rankingThresholds += tiles
    }

    override fun setDetailWindow(window: MatrixRtcDetailWindow) {
        detailWindows += window
        // The core republishes on every declaration, so a tile that has entered the window arrives
        // with its record without a roster change.
        pushedRoster?.let { tiles.value = it.windowed(window) }
    }

    override val localState = MutableStateFlow<MatrixRtcLocalState?>(null)

    override val audioLevels = MutableStateFlow(emptyMap<String, MatrixRtcAudioLevel>())

    override val receiveStats = MutableStateFlow(emptyMap<MatrixRtcStreamRef, MatrixRtcReceiveStats>())

    /** Every set [setComposedTiles] was given, in order. */
    val composedTiles = mutableListOf<Set<MatrixRtcTileId>>()

    override fun setComposedTiles(tileIds: Set<MatrixRtcTileId>) {
        composedTiles += tileIds
    }

    private val _isMicrophoneMuted = MutableStateFlow(false)
    override val isMicrophoneMuted: StateFlow<Boolean> = _isMicrophoneMuted

    private val _isAudioTestToneEnabled = MutableStateFlow(false)
    override val isAudioTestToneEnabled: StateFlow<Boolean> = _isAudioTestToneEnabled

    private val _isCameraEnabled = MutableStateFlow(false)
    override val isCameraEnabled: StateFlow<Boolean> = _isCameraEnabled

    private val _isFrontCamera = MutableStateFlow(true)
    override val isFrontCamera: StateFlow<Boolean> = _isFrontCamera

    private val _isScreenSharing = MutableStateFlow(false)
    override val isScreenSharing: StateFlow<Boolean> = _isScreenSharing

    /** Streams [videoFrames] has been asked for, so a test can assert what the UI opened. */
    val videoFramesRequests = mutableListOf<MatrixRtcStreamRef>()

    /**
     * How many streams are currently open per member and kind.
     *
     * Counted on *collection* rather than on the call to [videoFrames], because that is the
     * distinction the real implementation makes: the flow is cold, so asking for it costs nothing
     * and collecting it opens a stream. A fake that returned `emptyFlow()` could not tell the two
     * apart, and so could not catch two tiles opening two streams on one member.
     */
    val openVideoStreams = mutableMapOf<MatrixRtcStreamRef, Int>()

    /**
     * How many streams have *ever* been opened, which [openVideoStreams] cannot show.
     *
     * A stream that closes and immediately reopens leaves the open count back at one, so a test
     * watching only that number cannot tell a stream that stayed up from one that was torn down and
     * rebuilt - and tearing it down and rebuilding it is the thing that crashes.
     */
    val videoStreamOpenCounts = mutableMapOf<MatrixRtcStreamRef, Int>()

    /** Zero rather than null for a stream never opened, which reads better in an assertion. */
    fun openVideoStreamsFor(memberId: String, kind: MatrixRtcStreamKind = MatrixRtcStreamKind.CAMERA): Int =
        openVideoStreams[MatrixRtcStreamRef(memberId, kind)] ?: 0

    fun videoStreamOpenCountFor(memberId: String, kind: MatrixRtcStreamKind = MatrixRtcStreamKind.CAMERA): Int =
        videoStreamOpenCounts[MatrixRtcStreamRef(memberId, kind)] ?: 0

    override fun videoFrames(memberId: String, kind: MatrixRtcStreamKind): Flow<MatrixRtcVideoFrame> {
        val ref = MatrixRtcStreamRef(memberId, kind)
        videoFramesRequests += ref
        frames?.let { return it(memberId, kind) }
        return flow {
            openVideoStreams[ref] = (openVideoStreams[ref] ?: 0) + 1
            videoStreamOpenCounts[ref] = (videoStreamOpenCounts[ref] ?: 0) + 1
            try {
                awaitCancellation()
            } finally {
                openVideoStreams[ref] = (openVideoStreams[ref] ?: 1) - 1
            }
        }
    }

    /** The mute each [publishMicrophone] was made with, in order: what the call joined as. */
    val publishMicrophoneCalls = mutableListOf<Boolean>()
    val publishMicrophoneCount get() = publishMicrophoneCalls.size
    var disconnectCount = 0
        private set

    /** Every value [setCameraEnabled] was called with, in order - not just the last one. */
    val cameraEnabledCalls = mutableListOf<Boolean>()
    var switchCameraCount = 0
        private set

    /** What [publishMicrophone] answers; the mute is recorded either way. */
    var publishMicrophoneResult: () -> Result<Unit> = { Result.success(Unit) }

    suspend fun emit(event: MatrixRtcCallEvent) = _events.emit(event)

    override suspend fun publishMicrophone(muted: Boolean): Result<Unit> {
        publishMicrophoneCalls += muted
        publishMicrophoneResult().onFailure { return Result.failure(it) }
        // As the real one does: the publication carries the mute, so the flow reports it from the
        // moment the microphone is up rather than only once someone toggles the button.
        _isMicrophoneMuted.value = muted
        return Result.success(Unit)
    }

    override suspend fun setMicrophoneMuted(muted: Boolean) {
        _isMicrophoneMuted.value = muted
    }

    override fun setAudioTestToneEnabled(enabled: Boolean) {
        _isAudioTestToneEnabled.value = enabled
    }

    override suspend fun setCameraEnabled(enabled: Boolean): Result<Unit> {
        cameraEnabledCalls += enabled
        camera?.let {
            if (enabled) {
                it.start()
                it.attachCount++
            } else {
                it.stop()
            }
        }
        _isCameraEnabled.value = enabled
        return Result.success(Unit)
    }

    override suspend fun switchCamera(): Result<Unit> {
        switchCameraCount++
        camera?.switchCamera()
        _isFrontCamera.value = !_isFrontCamera.value
        return Result.success(Unit)
    }

    /** Every (enabled, had a token) pair [setScreenShareEnabled] was called with, in order. */
    val screenShareCalls = mutableListOf<Pair<Boolean, Boolean>>()

    override suspend fun setScreenShareEnabled(enabled: Boolean, token: MatrixRtcScreenCaptureToken?): Result<Unit> {
        screenShareCalls += enabled to (token != null)
        // Mirrors the real one, which refuses to start without the user's grant rather than throwing.
        if (enabled && token == null) return Result.success(Unit)
        _isScreenSharing.value = enabled
        return Result.success(Unit)
    }

    /** Ends the share the way the system's cast notification does, with the app never asking. */
    fun stopScreenShareExternally() {
        _isScreenSharing.value = false
    }

    /**
     * Every constraint set, in order, keyed by stream.
     *
     * A list rather than the latest value: what matters is that a tile reports its size *once* when it
     * settles rather than on every frame of an animation, and only a history shows that.
     */
    val videoConstraints = mutableListOf<Pair<MatrixRtcStreamRef, MatrixRtcVideoConstraints>>()

    override suspend fun setVideoConstraints(
        memberId: String,
        kind: MatrixRtcStreamKind,
        constraints: MatrixRtcVideoConstraints,
    ): Result<Unit> {
        videoConstraints += MatrixRtcStreamRef(memberId, kind) to constraints
        return Result.success(Unit)
    }

    override suspend fun disconnect(): Result<Unit> {
        disconnectCount++
        return Result.success(Unit)
    }

    override fun close() = Unit
}

/**
 * Our camera, with no device: frames from [frames] while started, and every start and stop counted, so a test can say the
 * device opened once from the lobby into the call (feature-hq 045 R49).
 */
class FakeMatrixRtcLocalCamera(
    private val pictures: Flow<MatrixRtcVideoFrame> = emptyFlow(),
    /** What [start] answers. */
    var startResult: () -> Result<Unit> = { Result.success(Unit) },
) : MatrixRtcLocalCamera {
    override val isCapturing = MutableStateFlow(false)
    override val isFrontFacing = MutableStateFlow(true)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val frames: Flow<MatrixRtcVideoFrame> = isCapturing.flatMapLatest { if (it) pictures else emptyFlow() }

    var startCount = 0
        private set
    var stopCount = 0
        private set
    var switchCount = 0
        private set

    /** How many times a media session attached a track to it. */
    var attachCount = 0

    override suspend fun start(): Result<Unit> {
        if (isCapturing.value) return Result.success(Unit)
        startCount++
        return startResult().onSuccess { isCapturing.value = true }
    }

    override suspend fun switchCamera(): Result<Unit> {
        if (!isCapturing.value) return Result.success(Unit)
        switchCount++
        isFrontFacing.value = !isFrontFacing.value
        return Result.success(Unit)
    }

    override fun stop() {
        if (isCapturing.value) stopCount++
        isCapturing.value = false
    }
}
