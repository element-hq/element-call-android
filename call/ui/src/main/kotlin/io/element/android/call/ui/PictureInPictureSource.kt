/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import io.element.android.call.api.ElementCallWindowRect
import kotlin.math.roundToInt

/**
 * A modifier reporting where the tile carrying it is drawn, while it is the one picture-in-picture
 * would show ([isSource]): on every move, and null once it stops being that tile or leaves the screen,
 * so the window never grows out of a place where nothing is.
 *
 * The whole tile, not the part a scroll leaves visible: the window takes the tile's shape, and
 * `ElementCallPictureInPicture` drops the way in itself when the tile is not all on screen.
 */
@Composable
internal fun pictureInPictureSourceModifier(
    isSource: Boolean,
    onSourceChange: (ElementCallWindowRect?) -> Unit,
): Modifier {
    if (!isSource) return Modifier
    val currentOnSourceChange by rememberUpdatedState(onSourceChange)
    DisposableEffect(Unit) {
        onDispose { currentOnSourceChange(null) }
    }
    return Modifier.onGloballyPositioned { coordinates ->
        val position = coordinates.positionInWindow()
        val left = position.x.roundToInt()
        val top = position.y.roundToInt()
        currentOnSourceChange(
            ElementCallWindowRect(left = left, top = top, right = left + coordinates.size.width, bottom = top + coordinates.size.height)
        )
    }
}
