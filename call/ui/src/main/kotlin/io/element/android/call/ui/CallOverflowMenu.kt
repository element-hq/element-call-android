/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.element.android.call.ui.preview.ElementCallPreview
import io.element.android.call.ui.preview.PreviewsDayNight
import io.element.android.call.ui.theme.ElementCallTheme

/**
 * The top bar's three dots and what they open: for now, only Preferences (feature-hq 037 R12). The menu is kept, rather
 * than the button opening the sheet, because the design has more to come in it.
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
            CallOverflowMenuItems(
                onPreferences = {
                    isExpanded = false
                    state.eventSink(ElementCallScreenEvent.ShowPreferences)
                },
            )
        }
    }
}

@Composable
private fun CallOverflowMenuItems(onPreferences: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text = stringResource(R.string.element_call_preferences_title), color = ElementCallTheme.colors.textPrimary) },
        leadingIcon = { Icon(ElementCallTheme.icons.preferences, contentDescription = null, tint = ElementCallTheme.colors.iconSecondary) },
        onClick = onPreferences,
        modifier = Modifier.testTag(ElementCallTestTags.PREFERENCES),
    )
}

// The menu itself is a popup, which a screenshot cannot capture, so what it holds is previewed instead;
// the screen previews cover the button in the bar.
@PreviewsDayNight
@Composable
internal fun CallOverflowMenuItemsPreview() = ElementCallPreview {
    Column(modifier = Modifier.background(ElementCallTheme.colors.bgSubtlePrimary)) {
        CallOverflowMenuItems(onPreferences = {})
    }
}
