/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics

/**
 * A tile's tap, double tap and long press.
 *
 * Unlike `combinedClickable`, the single tap is reported on its own touch-up rather than after the
 * system's double-tap timeout, which made the chrome feel as if the tap had not worked. The screen
 * waits a shorter window of its own instead, which the double tap cancels (spec 014 R16). The second
 * tap of a double tap is not reported as a tap.
 *
 * Nothing is consumed on the way down and a consumed move or up cancels, so a drag past the slop is
 * never a tap (003 R65) and a button over the tile keeps its own tap (014 R15).
 */
@Composable
internal fun Modifier.tileTapGestures(
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onLongPress: () -> Unit,
): Modifier {
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnDoubleTap by rememberUpdatedState(onDoubleTap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    // Merging, as `combinedClickable` did: the tile reads as one node, and its tag takes the tile's place.
    return semantics(mergeDescendants = true) {
        onClick {
            currentOnTap()
            true
        }
        onLongClick {
            currentOnLongPress()
            true
        }
    }.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown()
            val firstUp = awaitTapUp(onLongPress = { currentOnLongPress() }) ?: return@awaitEachGesture
            currentOnTap()
            val secondDown = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitSecondDown(firstUp) }
            if (secondDown != null && awaitTapUp(onLongPress = { currentOnLongPress() }) != null) currentOnDoubleTap()
        }
    }
}

/** A tap on the stage that nothing above it took: between tiles, below the last row, on a pill (014 R14). */
@Composable
internal fun Modifier.stageGapTaps(onTap: () -> Unit): Modifier {
    val currentOnTap by rememberUpdatedState(onTap)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown()
            if (waitForUpOrCancellation()?.apply { consume() } != null) currentOnTap()
        }
    }
}

/** The up ending a tap, consumed; null if the gesture became something else, a long press included. */
private suspend fun AwaitPointerEventScope.awaitTapUp(onLongPress: () -> Unit): PointerInputChange? {
    return try {
        withTimeout(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }?.apply { consume() }
    } catch (_: PointerEventTimeoutCancellationException) {
        onLongPress()
        do {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
        null
    }
}

/** The next down far enough after [firstUp] to be a second tap rather than a bounce of the first. */
private suspend fun AwaitPointerEventScope.awaitSecondDown(firstUp: PointerInputChange): PointerInputChange {
    val minUptime = firstUp.uptimeMillis + viewConfiguration.doubleTapMinTimeMillis
    var change: PointerInputChange
    do {
        change = awaitFirstDown()
    } while (change.uptimeMillis < minUptime)
    return change
}
