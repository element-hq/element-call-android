/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Drags our floating tile and lets it go into a corner (019 R21, R22).
 *
 * On the parent of the camera button, so a drag that starts on the button moves the tile: past the
 * touch slop the moves are consumed, which cancels the button's press, and a tap below the slop stays
 * the button's. The tile follows the finger through [drag], an offset its slot adds at layout time;
 * on release the corner changes and the offset springs to zero, so the tile travels on from under the
 * finger rather than jumping to where its slot was.
 *
 * @param drag the offset from the tile's corner, zero at rest.
 * @param slot the tile's rect without the drag, in stage coordinates.
 * @param stage what the tile may not be dragged out of.
 * @param area what the corners are measured in: the stage less the visible chrome.
 * @param onRelease told the corner a release goes to.
 */
internal fun Modifier.ownTileDragGesture(
    drag: Animatable<Offset, AnimationVector2D>,
    slot: () -> Rect,
    stage: () -> Rect,
    area: () -> Rect,
    onRelease: (ElementCallOwnTileCorner) -> Unit,
): Modifier = pointerInput(drag) {
    coroutineScope {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var total = drag.value
            val tracker = VelocityTracker()
            val start = awaitTouchSlopOrCancellation(down.id) { change, overSlop ->
                change.consume()
                total += overSlop
            }
            if (start == null) {
                // A tap on our tile is the tile's, and does nothing: not the stage's chrome toggle (019 R23).
                currentEvent.changes.forEach { it.consume() }
                return@awaitEachGesture
            }
            var lastMoveMs = start.uptimeMillis
            tracker.addPosition(lastMoveMs, total)
            fun follow() {
                val clamped = SmallCallLayout.clampedDrag(slot(), total, stage())
                launch { drag.snapTo(clamped) }
            }
            follow()
            val isReleased = drag(start.id) { change ->
                total += change.positionChange()
                change.consume()
                lastMoveMs = change.uptimeMillis
                tracker.addPosition(lastMoveMs, total)
                follow()
            }
            val releaseMs = currentEvent.changes.firstOrNull()?.uptimeMillis ?: lastMoveMs
            // A finger that rested before letting go has no motion left to carry (contract C5).
            val velocity = if (!isReleased || releaseMs - lastMoveMs > RELEASE_PAUSE_MS) Velocity.Zero else tracker.calculateVelocity()
            if (isReleased) {
                val center = slot().center + SmallCallLayout.clampedDrag(slot(), total, stage())
                onRelease(SmallCallLayout.releaseCorner(center, Offset(velocity.x, velocity.y), area()))
            }
            launch {
                // The new corner reaches the slot on the next frame; the offset unwinds alongside it.
                withFrameNanos { }
                drag.animateTo(Offset.Zero, RELEASE_SPEC)
            }
        }
    }
}

/** A release this long after the last movement is a drop, whatever the tracker remembers (contract C5). */
private const val RELEASE_PAUSE_MS = 100L

/** [SLOT_SPEC]'s twin, so the offset and the slot it is added to move as one. */
private val RELEASE_SPEC = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = Offset(0.5f, 0.5f),
)
