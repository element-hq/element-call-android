/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc.media

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Which of a camera's formats to open, and the size every published frame is scaled to.
 *
 * The published size is what LiveKit derives the simulcast layers from, so it is held at 720 on the
 * short edge: the layers are then 180, 360 and 720 tall, as a web sender's are. The shape is the
 * sensor's, never 16:9 cut out of a 4:3 sensor.
 *
 * @param candidate the format to ask the camera for.
 * @param output what every frame is scaled to and what the track is declared as; the candidate's own
 * size when it needs no scaling.
 */
internal data class CameraCaptureFormat(
    val candidate: Candidate,
    val output: Size,
) {
    /** One of the camera's formats, in the sensor's landscape orientation. */
    data class Candidate(
        val width: Int,
        val height: Int,
        val maxFrameRate: Int,
    ) {
        val shortEdge: Int get() = min(width, height)
        val longEdge: Int get() = max(width, height)
        val area: Int get() = width * height
        val aspectRatio: Float get() = longEdge.toFloat() / shortEdge
    }

    data class Size(
        val width: Int,
        val height: Int,
    )

    companion object {
        const val TARGET_SHORT_EDGE = 720

        /** Below this the core publishes a single layer. */
        const val MINIMUM_LONG_EDGE = 480
        const val FRAME_RATE = 30

        private const val ASPECT_TOLERANCE = 0.01f

        /**
         * @param candidates the camera's formats.
         * @param nativeAspect the sensor's long edge over its short edge. Not the largest candidate's:
         * the list is SurfaceTexture sizes, which some devices cap at 1920x1080 on a 4:3 sensor.
         */
        fun choose(candidates: List<Candidate>, nativeAspect: Float): CameraCaptureFormat? {
            val usable = candidates.filter { it.shortEdge > 0 }
            if (usable.isEmpty()) return null
            val closestShape = usable.minOf { abs(it.aspectRatio - nativeAspect) }
            val shaped = usable.filter { abs(it.aspectRatio - nativeAspect) - closestShape <= ASPECT_TOLERANCE * nativeAspect }
            val pool = shaped.filter { it.maxFrameRate >= FRAME_RATE }.ifEmpty { shaped }

            pool.filter { it.shortEdge >= TARGET_SHORT_EDGE }.minByOrNull { it.area }?.let { chosen ->
                return CameraCaptureFormat(chosen, scaledToShortEdge(chosen.width, chosen.height, TARGET_SHORT_EDGE))
            }
            // A camera that cannot reach 720 runs the closest it has, unscaled.
            val chosen = pool.filter { it.longEdge >= MINIMUM_LONG_EDGE }.ifEmpty { pool }.maxBy { it.area }
            return CameraCaptureFormat(chosen, Size(chosen.width, chosen.height))
        }

        /** [width] by [height] with its short edge brought down to [shortEdge], rounded to even; unchanged if already there or below. */
        fun scaledToShortEdge(width: Int, height: Int, shortEdge: Int): Size {
            val currentShortEdge = min(width, height)
            if (currentShortEdge <= shortEdge) return Size(width, height)
            val longEdge = (max(width, height).toFloat() * shortEdge / currentShortEdge / 2).roundToInt() * 2
            return if (width >= height) Size(longEdge, shortEdge) else Size(shortEdge, longEdge)
        }
    }
}
