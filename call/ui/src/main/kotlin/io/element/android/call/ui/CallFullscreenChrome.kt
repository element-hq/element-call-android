/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalLayoutApi::class)

package io.element.android.call.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.element.android.call.ui.theme.ElementCallTheme

/**
 * The HUD over a tile filling the stage (spec 000 R8, R11): a way out of fullscreen, the tile's name
 * with its mute state, and the call's controls. Hidden on entry and toggled by a single tap on the
 * tile (000 R9); whether it is shown survives a rotation (000 R10).
 *
 * The close button leaves fullscreen and nothing else: it is not the minimise button and does not
 * put the call in Picture in Picture (000 R12), so it shares neither its label nor its tag.
 */
@Composable
internal fun CallFullscreenChrome(
    tile: CallTileData,
    onExitFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    controls: @Composable (Modifier) -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                // Ignoring visibility: held sideways the status bar comes and goes with this HUD (014 R8).
                .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility)
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onExitFullscreen,
                modifier = Modifier.testTag(ElementCallTestTags.EXIT_FULLSCREEN),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = ElementCallTheme.colors.overlayScrim,
                    contentColor = ElementCallTheme.colors.onOverlay,
                ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.CloseFullscreen,
                    contentDescription = stringResource(R.string.element_call_a11y_exit_fullscreen),
                )
            }
            NamePill(tile = tile, modifier = Modifier.padding(start = 8.dp))
        }
        controls(Modifier.align(Alignment.BottomCenter))
    }
}
