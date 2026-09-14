/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc.media

import io.element.android.call.impl.util.runCatchingExceptions
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import livekit.org.webrtc.VideoFrame
import timber.log.Timber
import uniffi.matrix_rtc_ffi.FfiLocalTrack
import uniffi.matrix_rtc_ffi.FfiVideoFrameData
import uniffi.matrix_rtc_ffi.FfiVideoRotation

/**
 * Turns a captured frame into a published one: repack the planes, hand them to the FFI, and pass a
 * copy back for a local preview.
 *
 * Shared by the camera and the screen because the two differ only in where the frames come from -
 * everything from `toI420()` onwards is identical, down to the reasons for it, and the one thing
 * worse than this indirection would be two copies of it drifting apart. Each capturer owns its own
 * instance, so the frame counter and the warned-once flags are per capture rather than per process.
 *
 * Not thread-safe, and does not need to be: a capturer delivers frames on one thread and this is only
 * ever called from there.
 *
 * @param label what this is capturing, for the log line. "camera" or "screen".
 * @param framesPerLog how many frames between log lines, so a 30fps camera and a 5fps screen share
 * both report about as often.
 */
internal class VideoFramePublisher(
    private val label: String,
    private val framesPerLog: Long,
    private val onFrame: (MatrixRtcVideoFrame) -> Unit = {},
) {
    private var frameCount = 0L
    private var hasWarnedAboutConversion = false
    private var hasWarnedAboutPublishing = false

    fun reset() {
        frameCount = 0L
        hasWarnedAboutConversion = false
        hasWarnedAboutPublishing = false
    }

    /** Frames delivered since the last [reset], for a "did anything happen" check. */
    val publishedFrameCount: Long get() = frameCount

    /**
     * Convert [frame] and publish it to [track].
     *
     * The frame is not ours to release - the capturer's session releases it once the callback
     * returns, and a consumer that wants to keep it must retain it. We copy instead, so we never do.
     */
    fun publish(track: FfiLocalTrack, frame: VideoFrame) {
        val i420 = frame.buffer.toI420()
        if (i420 == null) {
            // Once. A conversion that fails once fails every frame, and at capture rate this would
            // otherwise be the only thing in the log.
            if (!hasWarnedAboutConversion) {
                hasWarnedAboutConversion = true
                Timber.w("MatrixRTC: cannot convert a captured $label frame to I420, no video will be published")
            }
            return
        }
        try {
            publish(track, i420, frame.rotation, frame.timestampNs)
        } finally {
            i420.release()
        }
    }

    private fun publish(track: FfiLocalTrack, i420: VideoFrame.I420Buffer, rotationDegrees: Int, timestampNs: Long) {
        val width = i420.width
        val height = i420.height
        val chromaWidth = I420Buffers.chromaWidth(width)
        val chromaHeight = I420Buffers.chromaHeight(height)

        val packedY = I420Buffers.packPlane(i420.dataY, i420.strideY, width, height)
        val packedU = I420Buffers.packPlane(i420.dataU, i420.strideU, chromaWidth, chromaHeight)
        val packedV = I420Buffers.packPlane(i420.dataV, i420.strideV, chromaWidth, chromaHeight)
        val timestampUs = timestampNs / NANOS_PER_MICRO

        // Straight into the FFI from the capture thread, deliberately not through the session's
        // single-threaded FFI dispatcher. captureVideo does not suspend, and AudioCapture already
        // pushes frames the same way: putting 30 frames a second through that one thread would
        // queue them behind membership and stats calls for no benefit.
        runCatchingExceptions {
            track.captureVideo(
                FfiVideoFrameData(
                    width = width.toUInt(),
                    height = height.toUInt(),
                    // Passed on rather than applied: the frame is stored the way it was read and the
                    // far end turns it upright, which is a rotation we would otherwise have to do in
                    // software on every frame.
                    rotation = rotationDegrees.toFfiRotation(),
                    timestampUs = timestampUs,
                    dataY = packedY,
                    strideY = width.toUInt(),
                    dataU = packedU,
                    strideU = chromaWidth.toUInt(),
                    dataV = packedV,
                    strideV = chromaWidth.toUInt(),
                )
            )
        }.onFailure {
            if (!hasWarnedAboutPublishing) {
                hasWarnedAboutPublishing = true
                Timber.w(it, "MatrixRTC: cannot publish a captured $label frame, no video will reach the call")
            }
        }

        // After publishing, and outside the failure branch above: a preview that keeps working while
        // nothing reaches the call is what separates "the capture is broken" from "the transport is".
        onFrame(
            MatrixRtcVideoFrame(
                width = width,
                height = height,
                rotationDegrees = rotationDegrees,
                timestampUs = timestampUs,
                dataY = I420Buffers.toDirectBuffer(packedY),
                strideY = width,
                dataU = I420Buffers.toDirectBuffer(packedU),
                strideU = chromaWidth,
                dataV = I420Buffers.toDirectBuffer(packedV),
                strideV = chromaWidth,
            )
        )

        frameCount++
        // The dimensions and rotation are here because the capturer picks the format, not us: what it
        // settled on is only visible from a frame.
        if (frameCount == 1L || frameCount % framesPerLog == 0L) {
            Timber.i("MatrixRTC: captured $frameCount $label frame(s), ${width}x$height rotated $rotationDegrees")
        }
    }

    private companion object {
        const val NANOS_PER_MICRO = 1_000L
    }
}

/**
 * Anything other than the four right angles is not representable, and a capturer only ever reports
 * those - so an unexpected value is a bug rather than a frame to drop, and treating it as upright
 * keeps the picture flowing while the log says what happened.
 */
private fun Int.toFfiRotation(): FfiVideoRotation = when (this) {
    0 -> FfiVideoRotation.DEG0
    90 -> FfiVideoRotation.DEG90
    180 -> FfiVideoRotation.DEG180
    270 -> FfiVideoRotation.DEG270
    else -> {
        Timber.w("MatrixRTC: unexpected frame rotation $this, sending it as upright")
        FfiVideoRotation.DEG0
    }
}
