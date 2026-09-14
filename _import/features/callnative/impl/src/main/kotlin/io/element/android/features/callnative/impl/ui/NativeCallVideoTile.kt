/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.callnative.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.matrixrtc.api.MatrixRtcVideoFrame
import kotlinx.coroutines.flow.Flow

/**
 * A member's video as the diagnostics screen shows it: one fixed-height row in a scrolling list.
 *
 * Aspect fit rather than fill, deliberately - that comes from [CallVideoRenderer]. Filling looks
 * tidier, but this screen exists to show what is actually arriving, and a frame with the wrong
 * dimensions or an unapplied rotation is invisible once it has been cropped to the box.
 *
 * @param frames the stream to draw. Nothing is shown until the first frame arrives.
 * @param isMirrored whether to flip horizontally. True for our own front camera and nothing else.
 */
@Composable
fun NativeCallVideoTile(
    frames: Flow<MatrixRtcVideoFrame>,
    isMirrored: Boolean,
    modifier: Modifier = Modifier,
) {
    CallVideoRenderer(
        frames = frames,
        isMirrored = isMirrored,
        modifier = modifier
            .fillMaxWidth()
            .height(TILE_HEIGHT)
            .clip(RoundedCornerShape(8.dp))
            .background(ElementTheme.colors.bgSubtlePrimary),
    )
}

private val TILE_HEIGHT = 180.dp
