/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import io.element.android.call.api.rtc.MatrixRtcReaction
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Where a bubble is, how big, how tilted and how visible, at any moment of its flight (feature-hq 037 R1, R1a, R2, R3,
 * R21). A pure function of its seed, the same as iOS's, so one reaction draws the same path on both.
 *
 * Every distance is a fraction of the stage, so a landscape flight is the portrait one scaled down; the bubble is sized
 * from the stage's height, which is what makes it smaller in landscape, where a portrait-sized one read as a hop.
 */
internal class ElementCallReactionBubbleMotion(seed: Long, spread: Duration = Duration.ZERO) {
    /** In the stage's own units: [centerX], [centerY] from its top left. */
    data class Frame(
        val centerX: Float,
        val centerY: Float,
        val scale: Float,
        val rotationDegrees: Float,
        val alpha: Float,
        /** How far the round is stretched across and up, each near 1 and out of step with the other (R3). */
        val flexX: Float,
        val flexY: Float,
    )

    /** From appearing to gone: four seconds from arrival, less what it waited (R1a). */
    val lifetime: Duration

    /** Fully visible until three seconds from arrival, then fading (R1a). */
    private val visibleFor: Duration

    /** Where across the stage it rises, as a fraction of the width, clear of the edges (R2). */
    val startX: Float
    private val swayAmplitude: Float
    private val swayFrequencies: Pair<Float, Float>
    private val swayPhases: Pair<Float, Float>
    private val flexFrequency: Float
    private val flexPhase: Float

    init {
        val waited = spread.coerceIn(Duration.ZERO, MAXIMUM_SPREAD)
        lifetime = MatrixRtcReaction.LIFETIME - waited
        visibleFor = MatrixRtcReaction.VISIBLE_FOR - waited
        val random = SplitMix64(seed)
        startX = 0.1f + 0.8f * random.unit()
        swayAmplitude = 0.6f + 0.4f * random.unit()
        swayFrequencies = Pair(0.55f + 0.3f * random.unit(), 1.3f + 0.6f * random.unit())
        swayPhases = Pair(TWO_PI * random.unit(), TWO_PI * random.unit())
        flexFrequency = 0.9f + 0.4f * random.unit()
        flexPhase = TWO_PI * random.unit()
    }

    fun frame(elapsed: Duration, stageWidth: Float, stageHeight: Float): Frame {
        val seconds = elapsed.inWholeMicroseconds / 1_000_000f
        val risen = rise(seconds / lifetime.inSeconds())

        // Growing on the same ease, so it is full size by a third of the way up.
        val growth = rise(risen / FULL_SIZE_AT)
        val size = size(stageHeight)
        val scale = size * (START_SCALE + (1 - START_SCALE) * growth)

        // From just below the bottom edge, behind the controls, to just past the top.
        val height = DIAMETER * 2 * size
        val y = stageHeight + height / 2 - risen * (stageHeight + height)

        // Two sines, the slower leading, swaying more as it rises; the tilt leans into the slower one's travel.
        val amplitude = 0.03f * min(stageWidth, stageHeight) * swayAmplitude * (0.4f + 0.6f * risen)
        val first = TWO_PI * swayFrequencies.first * seconds + swayPhases.first
        val second = TWO_PI * swayFrequencies.second * seconds + swayPhases.second
        val x = startX * stageWidth + amplitude * (0.7f * sin(first) + 0.3f * sin(second))

        val fadeIn = min(seconds / FADE_IN.inSeconds(), 1f)
        val visible = visibleFor.inSeconds()
        val fadeOut = if (seconds <= visible) 1f else max(0f, 1 - (seconds - visible) / (lifetime.inSeconds() - visible))

        // Wider as it gets shorter and back, a quarter turn apart, so the round flexes rather than pulses.
        val flex = TWO_PI * flexFrequency * seconds + flexPhase
        return Frame(
            centerX = x,
            centerY = y,
            scale = scale,
            rotationDegrees = 5 * cos(first),
            alpha = max(0f, min(fadeIn, fadeOut)),
            flexX = 1 + FLEX_AMPLITUDE * sin(flex),
            flexY = 1 + FLEX_AMPLITUDE * sin(flex + PI.toFloat() / 2),
        )
    }

    companion object {
        /** The design size, in dp, which the motion scales. */
        const val DIAMETER = 52f
        const val START_SCALE = 0.55f
        const val FULL_SIZE_AT = 1f / 3
        const val FLEX_AMPLITUDE = 0.1f
        private const val SIZE_FOR_HEIGHT = 0.065f
        private const val MIN_SIZE = 0.6f
        private const val MAX_SIZE = 1.1f
        private val FADE_IN = 150.milliseconds
        private val MAXIMUM_SPREAD = 500.milliseconds
        private const val TWO_PI = (2 * PI).toFloat()

        /** How far up it has come, 0 to 1: fastest at first, then slowing (R3). */
        fun rise(progress: Float): Float {
            val clamped = progress.coerceIn(0f, 1f)
            return 1 - (1 - clamped) * (1 - clamped)
        }

        /** The bubble's size for a stage this tall, relative to [DIAMETER] (R21). */
        fun size(stageHeight: Float): Float = (stageHeight * SIZE_FOR_HEIGHT / DIAMETER).coerceIn(MIN_SIZE, MAX_SIZE)

        private fun Duration.inSeconds(): Float = inWholeMicroseconds / 1_000_000f
    }
}

/** iOS's generator, so a bubble's path follows from its reaction's id alone, the same on both platforms. */
private class SplitMix64(private var state: Long) {
    fun next(): Long {
        state += -0x61c8864680b583ebL
        var value = state
        value = (value xor (value ushr 30)) * -0x40a7b892e31b1a47L
        value = (value xor (value ushr 27)) * -0x6b2fb644ecceee15L
        return value xor (value ushr 31)
    }

    /** Uniform in 0 until 1. */
    fun unit(): Float = ((next() ushr 11).toDouble() / (1L shl 53).toDouble()).toFloat()
}
