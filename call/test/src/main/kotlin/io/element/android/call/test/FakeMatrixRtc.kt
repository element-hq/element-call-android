/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.test

import io.element.android.call.api.rtc.MatrixRtcAudioLevel
import io.element.android.call.api.rtc.MatrixRtcCall
import io.element.android.call.api.rtc.MatrixRtcCallEvent
import io.element.android.call.api.rtc.MatrixRtcElementCallCompat
import io.element.android.call.api.rtc.MatrixRtcLeaveReason
import io.element.android.call.api.rtc.MatrixRtcMembership
import io.element.android.call.api.rtc.MatrixRtcNotify
import io.element.android.call.api.rtc.MatrixRtcParticipant
import io.element.android.call.api.rtc.MatrixRtcReceiveStats
import io.element.android.call.api.rtc.MatrixRtcScreenCaptureToken
import io.element.android.call.api.rtc.MatrixRtcService
import io.element.android.call.api.rtc.MatrixRtcSession
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcTransport
import io.element.android.call.api.rtc.MatrixRtcVideoConstraints
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import io.element.android.call.api.rtc.id.RoomId
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

class FakeMatrixRtcService(
    private val transports: List<MatrixRtcTransport> = emptyList(),
    private val joinResult: () -> Result<MatrixRtcSession>? = { null },
) : MatrixRtcService {
    var joinCallCount = 0
        private set
    var lastSession: FakeMatrixRtcSession? = null
        private set
    var startCount = 0
        private set
    var lastElementCallCompat: MatrixRtcElementCallCompat? = null
        private set
    var lastNotify: MatrixRtcNotify? = null
        private set

    override suspend fun start() {
        startCount++
    }

    override suspend fun discoverTransports(): Result<List<MatrixRtcTransport>> = Result.success(transports)

    override suspend fun joinSession(
        roomId: RoomId,
        slotId: String,
        application: String,
        transport: MatrixRtcTransport?,
        elementCallCompat: MatrixRtcElementCallCompat,
        notify: MatrixRtcNotify?,
    ): Result<MatrixRtcSession> {
        joinCallCount++
        lastElementCallCompat = elementCallCompat
        lastNotify = notify
        joinResult()?.let { return it }
        return Result.success(FakeMatrixRtcSession(roomId, slotId).also { lastSession = it })
    }
}

class FakeMatrixRtcSession(
    override val roomId: RoomId,
    override val slotId: String,
) : MatrixRtcSession {
    override val members = MutableStateFlow(emptyList<MatrixRtcMembership>())
    override val memberCount = MutableStateFlow(0)

    var connectMediaCount = 0
        private set
    var leaveCount = 0
        private set
    var lastCall: FakeMatrixRtcCall? = null
        private set

    override suspend fun connectMedia(transport: MatrixRtcTransport.LiveKit): Result<MatrixRtcCall> {
        connectMediaCount++
        return Result.success(FakeMatrixRtcCall().also { lastCall = it })
    }

    override suspend fun leave(reason: MatrixRtcLeaveReason?): Result<Unit> {
        leaveCount++
        return Result.success(Unit)
    }

    override fun close() = Unit
}

class FakeMatrixRtcCall : MatrixRtcCall {
    override val localMemberId: String = "aLocalMemberId"

    private val _events = MutableSharedFlow<MatrixRtcCallEvent>(extraBufferCapacity = 8)
    override val events: Flow<MatrixRtcCallEvent> = _events

    override val participants = MutableStateFlow(emptyList<MatrixRtcParticipant>())

    override val audioLevels = MutableStateFlow(emptyMap<String, MatrixRtcAudioLevel>())

    override val receiveStats = MutableStateFlow(emptyMap<String, MatrixRtcReceiveStats>())

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

    /** One member's one video stream: what the real implementation keys its flows by. */
    data class VideoStreamRef(val memberId: String, val kind: MatrixRtcStreamKind)

    /** Streams [videoFrames] has been asked for, so a test can assert what the UI opened. */
    val videoFramesRequests = mutableListOf<VideoStreamRef>()

    /**
     * How many streams are currently open per member and kind.
     *
     * Counted on *collection* rather than on the call to [videoFrames], because that is the
     * distinction the real implementation makes: the flow is cold, so asking for it costs nothing
     * and collecting it opens a stream. A fake that returned `emptyFlow()` could not tell the two
     * apart, and so could not catch two tiles opening two streams on one member.
     */
    val openVideoStreams = mutableMapOf<VideoStreamRef, Int>()

    /**
     * How many streams have *ever* been opened, which [openVideoStreams] cannot show.
     *
     * A stream that closes and immediately reopens leaves the open count back at one, so a test
     * watching only that number cannot tell a stream that stayed up from one that was torn down and
     * rebuilt - and tearing it down and rebuilding it is the thing that crashes.
     */
    val videoStreamOpenCounts = mutableMapOf<VideoStreamRef, Int>()

    /** Zero rather than null for a stream never opened, which reads better in an assertion. */
    fun openVideoStreamsFor(memberId: String, kind: MatrixRtcStreamKind = MatrixRtcStreamKind.CAMERA): Int =
        openVideoStreams[VideoStreamRef(memberId, kind)] ?: 0

    fun videoStreamOpenCountFor(memberId: String, kind: MatrixRtcStreamKind = MatrixRtcStreamKind.CAMERA): Int =
        videoStreamOpenCounts[VideoStreamRef(memberId, kind)] ?: 0

    override fun videoFrames(memberId: String, kind: MatrixRtcStreamKind): Flow<MatrixRtcVideoFrame> {
        val ref = VideoStreamRef(memberId, kind)
        videoFramesRequests += ref
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

    suspend fun emit(event: MatrixRtcCallEvent) = _events.emit(event)

    override suspend fun publishMicrophone(muted: Boolean): Result<Unit> {
        publishMicrophoneCalls += muted
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
        _isCameraEnabled.value = enabled
        return Result.success(Unit)
    }

    override suspend fun switchCamera(): Result<Unit> {
        switchCameraCount++
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
    val videoConstraints = mutableListOf<Pair<VideoStreamRef, MatrixRtcVideoConstraints>>()

    override suspend fun setVideoConstraints(
        memberId: String,
        kind: MatrixRtcStreamKind,
        constraints: MatrixRtcVideoConstraints,
    ): Result<Unit> {
        videoConstraints += VideoStreamRef(memberId, kind) to constraints
        return Result.success(Unit)
    }

    override suspend fun disconnect(): Result<Unit> {
        disconnectCount++
        return Result.success(Unit)
    }

    override fun close() = Unit
}
