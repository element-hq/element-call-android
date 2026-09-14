/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.test

import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.nio.ByteBuffer

/**
 * Colour bars as a stream of I420 frames, for tiles that want real pixels without a camera.
 *
 * The avatar path hides every geometry bug - a crop, a letterbox, a surface stretched mid-resize - so
 * a harness that only ever draws avatars proves nothing about video. These frames go through the same
 * `videoFrames` lambda the product passes and the same renderer, and are built to show what goes
 * wrong: eight SMPTE bars whose widths reveal a stretch, a heavy white border that reveals a crop, and
 * a marker walking along the top edge that reveals a stalled stream. Some sizes are portrait and some
 * landscape, because rotation and aspect are where tiles break.
 *
 * Frames follow the reference-counting contract of [MatrixRtcVideoFrame]: the producer holds one
 * reference while a frame is in flight and releases it once the collector has returned. The planes
 * are direct heap buffers with no native memory behind them, so nothing is freed on release and a
 * renderer that retains a frame past that point is still safe.
 */
class ElementCallTestPattern(
    val width: Int,
    val height: Int,
    private val framesPerSecond: Int = DEFAULT_FRAMES_PER_SECOND,
) {
    init {
        require(width % 2 == 0 && height % 2 == 0) { "I420 needs even dimensions, got ${width}x$height" }
    }

    private val chromaWidth = width / 2
    private val chromaHeight = height / 2
    private val border = maxOf(MIN_BORDER, minOf(width, height) / BORDER_FRACTION)

    /** The bars and the border, drawn once: the marker is the only thing that changes between frames. */
    private val baseY = ByteArray(width * height)
    private val dataU: ByteBuffer = ByteBuffer.allocateDirect(chromaWidth * chromaHeight)
    private val dataV: ByteBuffer = ByteBuffer.allocateDirect(chromaWidth * chromaHeight)

    init {
        drawBars()
    }

    /**
     * A cold stream at [framesPerSecond], for as long as it is collected. Each collector runs its own
     * producer, as a cold `MatrixRtcCall.videoFrames` would.
     */
    fun frames(): Flow<MatrixRtcVideoFrame> = flow {
        val frameDurationMs = MILLIS_PER_SECOND / framesPerSecond
        var index = 0L
        while (true) {
            val frame = frameAt(index++)
            emit(frame)
            // The collector has returned: whatever it still holds, it retained. This is the producer's own reference going.
            frame.release()
            delay(frameDurationMs)
        }
    }

    /** One frame, with the marker at the position for [index]. Retained once, for the caller. */
    fun frameAt(index: Long): MatrixRtcVideoFrame {
        val dataY = ByteBuffer.allocateDirect(width * height)
        dataY.put(baseY)
        drawMarker(dataY, index)
        dataY.rewind()
        return MatrixRtcVideoFrame(
            width = width,
            height = height,
            rotationDegrees = 0,
            timestampUs = index * MICROS_PER_SECOND / framesPerSecond,
            dataY = dataY,
            strideY = width,
            dataU = dataU.duplicate(),
            strideU = chromaWidth,
            dataV = dataV.duplicate(),
            strideV = chromaWidth,
        )
    }

    private fun drawBars() {
        val innerWidth = width - 2 * border
        for (y in 0 until height) {
            for (x in 0 until width) {
                val isBorder = x < border || x >= width - border || y < border || y >= height - border
                val bar = if (isBorder) WHITE else BARS[((x - border) * BARS.size / innerWidth).coerceIn(0, BARS.size - 1)]
                baseY[y * width + x] = bar.y
                if (x % 2 == 0 && y % 2 == 0) {
                    val chromaIndex = y / 2 * chromaWidth + x / 2
                    dataU.put(chromaIndex, bar.u)
                    dataV.put(chromaIndex, bar.v)
                }
            }
        }
    }

    /** A black square walking left to right inside the top border, one step per frame, wrapping. */
    private fun drawMarker(dataY: ByteBuffer, index: Long) {
        val size = border
        val travel = width - 2 * border - size
        if (travel <= 0) return
        val left = border + (index * MARKER_STEP % travel).toInt()
        for (y in 0 until size) {
            for (x in left until left + size) {
                dataY.put(y * width + x, BLACK.y)
            }
        }
    }

    /** Limited-range BT.601, which is what a camera produces and what the renderer expects. */
    private class Yuv(y: Int, u: Int, v: Int) {
        val y: Byte = y.toByte()
        val u: Byte = u.toByte()
        val v: Byte = v.toByte()
    }

    companion object {
        const val DEFAULT_FRAMES_PER_SECOND = 15
        private const val MILLIS_PER_SECOND = 1_000L
        private const val MICROS_PER_SECOND = 1_000_000L
        private const val MIN_BORDER = 4
        private const val BORDER_FRACTION = 16
        private const val MARKER_STEP = 4

        private val WHITE = Yuv(235, 128, 128)
        private val BLACK = Yuv(16, 128, 128)

        /** The SMPTE bars, in order: white, yellow, cyan, green, magenta, red, blue, black. */
        private val BARS = listOf(
            WHITE,
            Yuv(210, 16, 146),
            Yuv(170, 166, 16),
            Yuv(145, 54, 34),
            Yuv(106, 202, 222),
            Yuv(81, 90, 240),
            Yuv(41, 240, 110),
            BLACK,
        )

        /** A phone camera held upright. */
        fun portrait() = ElementCallTestPattern(width = 360, height = 640)

        /** A shared screen, or a camera held sideways. */
        fun landscape() = ElementCallTestPattern(width = 640, height = 360)
    }
}
