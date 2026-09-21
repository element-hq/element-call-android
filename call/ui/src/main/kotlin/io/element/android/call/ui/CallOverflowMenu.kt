/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.element.android.call.ui.preview.ElementCallPreview
import io.element.android.call.ui.preview.PreviewsDayNight
import io.element.android.call.ui.theme.ElementCallTheme

/**
 * The top bar's three dots and what they open: for now, which Element Call this is.
 *
 * Read-only on purpose. What people need from a menu mid-call is a version to put in a bug report, and
 * a line of text is the shortest path to it. Nothing in it is an action, so nothing here is an event.
 */
@Composable
internal fun CallOverflowMenu(state: ElementCallScreenState, modifier: Modifier = Modifier) {
    var isExpanded by remember { mutableStateOf(false) }
    // The menu anchors to the composable it is placed in, so the button and the menu share a box.
    Box(modifier = modifier) {
        IconButton(onClick = { isExpanded = true }) {
            Icon(
                imageVector = ElementCallTheme.icons.overflow,
                contentDescription = stringResource(R.string.element_call_a11y_more_options),
                tint = ElementCallTheme.colors.iconPrimary,
            )
        }
        DropdownMenu(
            expanded = isExpanded,
            onDismissRequest = { isExpanded = false },
            // Painted from this component's palette, as the audio device sheet is: Material would
            // otherwise take the host's theme, and a light-themed host got near-white text on white.
            containerColor = ElementCallTheme.colors.bgSubtlePrimary,
        ) {
            CallVersionMenuContent(libraryVersion = state.libraryVersion, coreVersion = state.coreVersion)
        }
    }
}

/**
 * The version lines. Plain text rather than menu items: there is nothing to tap, and a disabled item
 * would grey out the one thing the menu is for.
 */
@Composable
internal fun CallVersionMenuContent(
    libraryVersion: String,
    coreVersion: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = stringResource(R.string.element_call_version_library, libraryVersion),
            style = ElementCallTheme.typography.bodyMdRegular,
            color = ElementCallTheme.colors.textPrimary,
        )
        Text(
            text = stringResource(R.string.element_call_version_core, coreVersion),
            style = ElementCallTheme.typography.bodySmRegular,
            color = ElementCallTheme.colors.textSecondary,
        )
    }
}

// The menu itself is a popup, which a screenshot cannot capture, so what it holds is previewed instead;
// the screen previews cover the button in the bar.
@PreviewsDayNight
@Composable
internal fun CallVersionMenuContentPreview() = ElementCallPreview {
    CallVersionMenuContent(libraryVersion = "0.4.0", coreVersion = "0.3.0")
}
