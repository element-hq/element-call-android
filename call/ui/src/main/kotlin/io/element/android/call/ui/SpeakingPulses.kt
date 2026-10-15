/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.element.android.call.ui.theme.ElementCallTheme
import kotlinx.coroutines.delay

/**
 * Round the avatar of someone speaking with their camera off: a gradient ring, held apart by a gap, and pulses
 * that grow from behind it to [MAX_GROWTH] past the avatar, fading as they grow. A new pulse starts every
 * [SPAWN_MS] while they speak, so at most three overlap, and the ones under way finish when they stop. With
 * reduce motion, the ring alone.
 */
@Composable
internal fun SpeakingPulses(isSpeaking: Boolean, avatarSize: Dp, modifier: Modifier = Modifier) {
    val colors = ElementCallTheme.colors
    val isReduceMotion = rememberReduceMotion()
    val ringAlpha by animateFloatAsState(if (isSpeaking) 1f else 0f, tween(RING_FADE_MS), label = "speakingRing")
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (!isReduceMotion) Pulses(isSpeaking, from = avatarSize + RING_GROWTH + RING_GAP * 2, to = avatarSize + MAX_GROWTH)
        Box(modifier = Modifier.alpha(ringAlpha), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(avatarSize + RING_GROWTH + RING_GAP * 2)) { drawCircle(colors.bgCanvas) }
            // Its own canvas, so the gradient spans the ring rather than the pulses' area.
            Canvas(Modifier.size(avatarSize + RING_GROWTH)) {
                val width = RING_WIDTH.toPx()
                drawCircle(colors.borderSpeakingAvatar, radius = (size.minDimension - width) / 2, style = Stroke(width))
            }
        }
    }
}

@Composable
private fun Pulses(isSpeaking: Boolean, from: Dp, to: Dp) {
    val color = ElementCallTheme.colors.speakingPulse
    val starts = remember { mutableStateListOf<Long>() }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isSpeaking) {
        while (isSpeaking) {
            starts += withFrameNanos { it }
            delay(SPAWN_MS)
        }
    }
    LaunchedEffect(starts.isNotEmpty()) {
        while (starts.isNotEmpty()) {
            withFrameNanos { frame ->
                now = frame
                starts.removeAll { frame - it >= LIFETIME_NANOS }
            }
        }
    }
    Canvas(Modifier.size(to)) {
        val fromRadius = from.toPx() / 2
        val toRadius = to.toPx() / 2
        val edge = Stroke(PULSE_EDGE.toPx())
        starts.forEach { start ->
            val progress = ((now - start).toFloat() / LIFETIME_NANOS).coerceIn(0f, 1f)
            val radius = fromRadius + (toRadius - fromRadius) * progress
            val alpha = pulseAlpha(progress)
            drawCircle(color, radius = radius, alpha = alpha * PULSE_FILL_ALPHA)
            drawCircle(color, radius = radius, alpha = alpha * PULSE_EDGE_ALPHA, style = edge)
        }
    }
}

/** A pulse's strength [progress] through its life, from 0 to 1: a short fade in, then fading as it grows, gone at its largest. */
internal fun pulseAlpha(progress: Float): Float = (progress / FADE_IN).coerceAtMost(1f) * (1f - progress)

/** One pulse, from behind the ring to gone. */
private const val LIFETIME_MS = 1_600L
private const val LIFETIME_NANOS = LIFETIME_MS * 1_000_000

/** A third of a pulse's life: three at a time while someone speaks. */
private const val SPAWN_MS = LIFETIME_MS / 3

private const val FADE_IN = 0.1f
private const val PULSE_FILL_ALPHA = 0.16f
private const val PULSE_EDGE_ALPHA = 0.1f
private const val RING_FADE_MS = 300

/** Element Call web's outermost wave: 20 dp, then three steps of 40. */
private val MAX_GROWTH = 140.dp

/** The ring's diameter past the avatar's, the dark gap round it, and its stroke. */
private val RING_GROWTH = 12.dp
private val RING_GAP = 4.dp
private val RING_WIDTH = 2.dp
private val PULSE_EDGE = 1.dp
