/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc

import io.element.android.call.api.rtc.id.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A live media connection for a joined [MatrixRtcSession].
 *
 * The RTC library owns the transport and hands us decoded frames; capture and playback are ours.
 * Audio is handled entirely internally: microphone frames are captured and published, and every
 * remote audio stream is played back automatically, so a caller that only wants audio need do
 * nothing beyond [publishMicrophone].
 *
 * Video cannot work that way. Playback needs somewhere to draw, which only the UI has, so frames
 * are handed out on [videoFrames] for a renderer to consume and a caller that ignores them simply
 * sees nothing - and, for remote members, decodes nothing either.
 */
interface MatrixRtcCall : AutoCloseable {
    /**
     * Our own MSC4143 member id for this join.
     *
     * The same string the roster, the sticky events and every per-member report use, because the
     * core mints it once and hands it to all of them.
     */
    val localMemberId: String

    val events: Flow<MatrixRtcCallEvent>

    val participants: StateFlow<List<MatrixRtcParticipant>>

    /**
     * How loud each member is, keyed by member id, ours included.
     *
     * Measured on the PCM we actually capture and on the PCM the core actually decodes for us, so
     * this is the one place that tells us audio is really flowing rather than merely negotiated.
     */
    val audioLevels: StateFlow<Map<String, MatrixRtcAudioLevel>>

    /**
     * RTP receive counters per remote member, refreshed while media is connected.
     *
     * This is what [audioLevels] cannot tell you: whether packets are actually arriving. A member
     * appears once the transport has its first RTCP report, so an entry missing early in a call means
     * "not known yet" rather than "nothing received".
     */
    val receiveStats: StateFlow<Map<String, MatrixRtcReceiveStats>>

    /**
     * Whether our microphone is currently muted.
     *
     * Muting stops frames being captured rather than publishing silence, and tells the transport
     * as well - peers need to be able to tell a muted sender from one that has merely stopped
     * pushing frames, which is what a wedged client looks like.
     */
    val isMicrophoneMuted: StateFlow<Boolean>

    /** Whether we are publishing a synthetic tone in place of the microphone, see [setAudioTestToneEnabled]. */
    val isAudioTestToneEnabled: StateFlow<Boolean>

    /**
     * Whether our camera is capturing and publishing, see [setCameraEnabled].
     */
    val isCameraEnabled: StateFlow<Boolean>

    /**
     * Whether the camera in use faces the user, see [switchCamera].
     *
     * A self view has to mirror the front camera and must not mirror the back one - a front camera
     * shown unmirrored reads as broken, because it is the only image of yourself you ever see the
     * wrong way round. True before the camera has ever been opened, which is the side it starts on.
     */
    val isFrontCamera: StateFlow<Boolean>

    /**
     * Whether we are capturing and publishing the screen, see [setScreenShareEnabled].
     *
     * Goes false on its own if the user stops the share from the system UI, which is a thing they can
     * do at any moment from outside the app and which nothing else would report.
     */
    val isScreenSharing: StateFlow<Boolean>

    /**
     * Video frames for one of a member's streams, ours included.
     *
     * Cold, and one stream per collector: collecting opens the member's video stream and cancelling
     * closes it. Nothing is decoded for a stream nobody is drawing, which is the point - a tile that
     * has left the screen should stop costing bandwidth. Collect it once per member and kind; two
     * collectors means two streams.
     *
     * Frames are dropped rather than queued when a renderer cannot keep up, so being slow costs
     * latency in what is shown and never backpressure on the sender.
     *
     * Emits nothing, rather than failing, for a stream that is not being published - including
     * ourselves with the camera off. A member who starts publishing later needs a fresh collection:
     * this does not wait around for a stream that does not exist yet, so drive it from
     * [participants] and the [MatrixRtcCallEvent.StreamStarted] events that change it.
     *
     * @param memberId whose stream, as [participants] names them.
     * @param kind which of the member's video streams. A member can publish a camera and a screen at
     * once, and they are two independent streams that happen to share a member id.
     */
    fun videoFrames(memberId: String, kind: MatrixRtcStreamKind = MatrixRtcStreamKind.CAMERA): Flow<MatrixRtcVideoFrame>

    /**
     * Say how much of a member's video we actually need.
     *
     * The SFU sends whatever layer it thinks a subscriber wants, and with nothing said it sends the
     * best one - so a tile drawn 100dp wide receives, decodes and composites a full 720p frame to
     * show it at a tenth of that. Telling it the truth is what makes simulcast worth publishing.
     *
     * Idempotent and cheap; call it whenever a tile's size or visibility changes. Failure is reported
     * rather than thrown, and costs only the wasted bandwidth it was trying to save.
     */
    suspend fun setVideoConstraints(
        memberId: String,
        kind: MatrixRtcStreamKind,
        constraints: MatrixRtcVideoConstraints,
    ): Result<Unit>

    /**
     * Start capturing and publishing the microphone, [muted] deciding the state it is published in.
     *
     * Publishing an already-muted track is what a call joined muted needs: mute the publication
     * afterwards and every peer sees this member unmuted for as long as the mute takes to reach the
     * transport, which is exactly when the roster is being drawn for the first time.
     *
     * Requires [android.Manifest.permission.RECORD_AUDIO].
     */
    suspend fun publishMicrophone(muted: Boolean): Result<Unit>

    suspend fun setMicrophoneMuted(muted: Boolean)

    /**
     * Publish a fixed tone instead of what the microphone hears.
     *
     * A known signal at a known level takes the capture device out of the picture: if the far end
     * still sees nothing, the problem is downstream of capture. Emulators in particular hand out
     * silence unless the host microphone is explicitly shared with them.
     */
    fun setAudioTestToneEnabled(enabled: Boolean)

    /**
     * Start or stop capturing and publishing the camera.
     *
     * Requires [android.Manifest.permission.CAMERA]; failure is reported rather than thrown.
     *
     * Disabling releases the camera device, unlike [setMicrophoneMuted] which keeps the microphone
     * open and stops handing frames over. The indicator light going out is the point - a camera
     * that stays open while the UI says it is off is the kind of thing that costs trust. The track
     * itself stays published and is muted at the transport instead, so re-enabling does not have to
     * renegotiate and peers can tell a deliberate camera-off from a sender that has wedged.
     */
    suspend fun setCameraEnabled(enabled: Boolean): Result<Unit>

    /**
     * Swap between the front and back cameras. No effect if the camera is not currently enabled.
     */
    suspend fun switchCamera(): Result<Unit>

    /**
     * Start or stop capturing and publishing the screen.
     *
     * Unlike the camera this needs no permission, and instead a one-shot grant the user makes in a
     * system dialog. That dialog can only be raised by an Activity, so the token arrives here as
     * [MatrixRtcScreenCaptureToken] rather than being asked for from inside. It is spent on use:
     * sharing again after stopping means asking again.
     *
     * The caller must have a `mediaProjection` foreground service running before enabling this, or
     * Android 14 and above will refuse the projection.
     *
     * Disabling retracts the publication rather than muting it, which is the opposite of
     * [setCameraEnabled]. A camera-off is a state worth telling peers about; a screen that is not
     * being shared is not a state at all, and every client draws a tile for a screen-share stream
     * that exists, so a muted one leaves receivers showing an empty tile for a share that ended.
     *
     * @param enabled whether to start or stop sharing.
     * @param token the user's grant, required when enabling and ignored when disabling.
     */
    suspend fun setScreenShareEnabled(enabled: Boolean, token: MatrixRtcScreenCaptureToken? = null): Result<Unit>

    /**
     * Leave the transport. Does not leave the RTC session, see [MatrixRtcSession.leave].
     */
    suspend fun disconnect(): Result<Unit>
}

data class MatrixRtcParticipant(
    val memberId: String,
    val userId: UserId,
    /** Null when the transport has not resolved this participant to a Matrix device. */
    val deviceId: String?,
    val isLocal: Boolean,
    /** False once the transport considers the participant unreachable. */
    val isReachable: Boolean,
    val streams: List<MatrixRtcStreamState>,
)

data class MatrixRtcStreamState(
    val kind: MatrixRtcStreamKind,
    val isMuted: Boolean,
)

/**
 * A microphone meter reading for one member.
 *
 * On our own capture the two fields say what they look like: [level] is what the microphone hears
 * and [frameCount] is how much of it we handed over.
 *
 * On a remote member they are weaker evidence than they appear. The receive side pulls a frame every
 * [io.element.android.call.api.rtc.MatrixRtcStreamKind.MICROPHONE] tick whether or not
 * anything arrived - a jitter buffer with nothing to play emits silence rather than nothing - so a
 * counter rising at exactly real time only proves the stream is open. A [frameCount] that *stops*
 * still means the stream died, and a non-zero [level] still means real audio decoded; but zeros at a
 * steady rate say only "no sound", not whether packets are coming in.
 */
data class MatrixRtcAudioLevel(
    /** 0f for silence, 1f for full scale, derived from the RMS of the last window. */
    val level: Float,
    /** PCM frames measured since the stream opened. */
    val frameCount: Long,
    /**
     * Audio moved per second of wall clock, where 1f is real time. Null until the first interval has
     * been measured.
     *
     * A frame is a fixed 10 ms of audio, so a loop that is keeping up moves exactly one second of it
     * per second. Below 1f the loop is being handed less than it should, or is not being scheduled
     * often enough to take it - and the device buffer it feeds drains to empty in the gap. This is
     * the reading that separates "we are not keeping up" from every network explanation, which
     * [MatrixRtcReceiveStats] already covers.
     */
    val realtimeRatio: Float? = null,
    /**
     * `AudioTrack` under-runs since playback opened: buffers the platform wanted and we had not
     * refilled, each one audible as a crackle. Null for a capture meter, which has no track.
     */
    val underrunCount: Int? = null,
)

/**
 * Cumulative RTP receive counters for one remote member's stream.
 *
 * [concealedSamples] is the counter that resolves the ambiguity in [MatrixRtcAudioLevel]: it counts
 * the samples the jitter buffer invented because it had nothing to play. Concealment rising in step
 * with [totalSamplesReceived] means the silence we are playing is fabricated, not received.
 */
data class MatrixRtcReceiveStats(
    val packetsReceived: Long,
    /** Can be negative: the transport reports it as a signed delta against expected. */
    val packetsLost: Long,
    val bytesReceived: Long,
    /** Seconds, as reported by the transport. */
    val jitter: Double,
    val framesDecoded: Long,
    val framesDropped: Long,
    val totalSamplesReceived: Long,
    val concealedSamples: Long,
    /** Concealed samples the transport filled with silence rather than synthesised audio. */
    val silentConcealedSamples: Long,
    val concealmentEvents: Long,
) {
    /**
     * Share of played-out audio that was invented rather than received, 0f..1f.
     *
     * Null when nothing has been played out yet, which is not the same as zero concealment. A value
     * near 1f with [packetsReceived] stuck is the signature of a stream that is open but starved.
     */
    val concealedFraction: Float?
        get() = if (totalSamplesReceived <= 0L) null else (concealedSamples.toDouble() / totalSamplesReceived).toFloat()
}
