/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.element.android.call.ui.preview.ElementCallPreview
import io.element.android.call.ui.preview.PreviewsDayNight
import io.element.android.call.ui.theme.ElementCallTheme
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Received reactions, rising over the call like bubbles in sparkling water (feature-hq 037).
 *
 * A sibling of the stage, never part of a tile: a reaction changes nothing a tile reads, so no tile redraws (R5). It
 * collects its own reactions, so only it recomposes when one comes or goes, and its motion is read in the draw phase,
 * so a frame recomposes nothing. It takes no touches and is invisible to screen readers (R18).
 */
@Composable
internal fun ElementCallReactionsOverlay(reactions: Flow<ElementCallReactionBubbleData>, modifier: Modifier = Modifier) {
    val flights = remember { mutableStateListOf<Flight>() }
    val now = remember { mutableLongStateOf(0L) }
    LaunchedEffect(reactions) {
        reactions.collect { bubble ->
            // Past thirty, not shown and not kept for later; its sound has already been decided (R2a).
            if (flights.size < MAXIMUM_BUBBLES) flights += Flight(bubble, appearedAtNanos = null)
        }
    }
    // Only while something is in flight: a clock left running would keep the screen from ever going idle.
    val isFlying by remember { derivedStateOf { flights.isNotEmpty() } }
    LaunchedEffect(isFlying) {
        while (isFlying) {
            withFrameNanos { frameNanos ->
                now.longValue = frameNanos
                flights.forEachIndexed { index, flight ->
                    if (flight.appearedAtNanos == null) flights[index] = flight.copy(appearedAtNanos = frameNanos)
                }
                flights.removeAll { (it.appearedAtNanos ?: frameNanos) + it.motion.lifetime.inWholeNanoseconds < frameNanos }
            }
        }
    }
    BoxWithConstraints(modifier = modifier.fillMaxSize().clearAndSetSemantics {}) {
        val stageWidth = maxWidth.value
        val stageHeight = maxHeight.value
        val density = LocalDensity.current.density
        flights.forEach { flight ->
            key(flight.bubble.id) {
                ElementCallReactionBubble(
                    bubble = flight.bubble,
                    modifier = Modifier.graphicsLayer {
                        val appeared = flight.appearedAtNanos ?: now.longValue
                        val frame = flight.motion.frame((now.longValue - appeared).coerceAtLeast(0).nanoseconds, stageWidth, stageHeight)
                        // The bubble is laid out at the top left, so its centre is half its design size in.
                        val half = ElementCallReactionBubbleMotion.DIAMETER / 2
                        translationX = (frame.centerX - half) * density
                        translationY = (frame.centerY - half) * density
                        scaleX = frame.scale
                        scaleY = frame.scale
                        rotationZ = frame.rotationDegrees
                        alpha = frame.alpha
                        transformOrigin = TransformOrigin(0.5f, BUBBLE_CENTRE_Y)
                    },
                    flexX = { flight.motion.frame(elapsedOf(flight, now.longValue), stageWidth, stageHeight).flexX },
                    flexY = { flight.motion.frame(elapsedOf(flight, now.longValue), stageWidth, stageHeight).flexY },
                )
            }
        }
    }
}

private fun elapsedOf(flight: Flight, now: Long): Duration = (now - (flight.appearedAtNanos ?: now)).coerceAtLeast(0).nanoseconds

private data class Flight(val bubble: ElementCallReactionBubbleData, val appearedAtNanos: Long?) {
    val motion = ElementCallReactionBubbleMotion(seed = bubble.id, spread = bubble.spread)
}

/** The emoji in a round, the sender's name beneath. The round flexes on its own; the rest moves as one. */
@Composable
internal fun ElementCallReactionBubble(
    bubble: ElementCallReactionBubbleData,
    modifier: Modifier = Modifier,
    flexX: () -> Float = { 1f },
    flexY: () -> Float = { 1f },
) {
    Column(modifier = modifier.wrapContentSize(unbounded = true, align = Alignment.TopStart), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(ElementCallReactionBubbleMotion.DIAMETER.dp)
                .graphicsLayer {
                    scaleX = flexX()
                    scaleY = flexY()
                }
                .background(ElementCallTheme.colors.bgSubtleSecondary, CircleShape)
                .border(1.dp, ElementCallTheme.colors.borderControl, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = bubble.emoji, fontSize = 26.sp, textAlign = TextAlign.Center)
        }
        Text(
            text = if (bubble.isLocal) stringResource(R.string.element_call_you) else bubble.senderName,
            style = ElementCallTheme.typography.bodyXsMedium,
            color = ElementCallTheme.colors.textPrimary,
            maxLines = 1,
            // Shortened in the middle, never wrapped (R4).
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier
                .padding(top = 4.dp)
                .widthIn(max = MAXIMUM_NAME_WIDTH)
                .background(ElementCallTheme.colors.bgSubtleSecondary, RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

private const val MAXIMUM_BUBBLES = 30
private val MAXIMUM_NAME_WIDTH = 96.dp

/** Where the round's centre sits in the bubble's height, so it scales and tilts about the round and not the name. */
private const val BUBBLE_CENTRE_Y = 0.35f

@PreviewsDayNight
@Composable
internal fun ElementCallReactionBubblePreview() = ElementCallPreview {
    Box(modifier = Modifier.padding(16.dp)) {
        ElementCallReactionBubble(ElementCallReactionBubbleData(id = 1, emoji = "👏", senderName = "Carol", isLocal = false, spread = Duration.ZERO))
    }
}

@PreviewsDayNight
@Composable
internal fun ElementCallReactionBubbleLongNamePreview() = ElementCallPreview {
    Box(modifier = Modifier.padding(16.dp)) {
        ElementCallReactionBubble(
            ElementCallReactionBubbleData(id = 2, emoji = "🎉", senderName = "Wolfeschlegelsteinhausenbergerdorff", isLocal = false, spread = Duration.ZERO)
        )
    }
}

@PreviewsDayNight
@Composable
internal fun ElementCallReactionBubbleOwnPreview() = ElementCallPreview {
    Box(modifier = Modifier.padding(16.dp)) {
        ElementCallReactionBubble(
            ElementCallReactionBubbleData(id = 3, emoji = "🐶", senderName = "@alice:example.org", isLocal = true, spread = 0.milliseconds)
        )
    }
}
