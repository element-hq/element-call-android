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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.call.ui.preview.ElementCallPreview
import io.element.android.call.ui.preview.PreviewsDayNight
import io.element.android.call.ui.theme.ElementCallTheme

/** A sheet the settings menu opens. Each opens over the call and survives rotation and backgrounding (feature-hq 043 R5). */
enum class ElementCallSettingsSheet {
    PREFERENCES,

    /** Only in developer mode (043 R4). */
    DEVELOPER_OPTIONS,
}

/**
 * The top bar's settings button and the menu it opens (feature-hq 043 R1–R5): Preferences, then Developer options in developer
 * mode. Each entry closes the menu and opens its own sheet. The design has more entries to come above Preferences.
 */
@Composable
internal fun CallSettingsMenu(state: ElementCallScreenState, modifier: Modifier = Modifier) {
    var isExpanded by remember { mutableStateOf(false) }
    // The menu anchors to the composable it is placed in, so the button and the menu share a box.
    Box(modifier = modifier) {
        IconButton(onClick = { isExpanded = true }, modifier = Modifier.testTag(ElementCallTestTags.SETTINGS)) {
            Icon(
                imageVector = ElementCallTheme.icons.settings,
                contentDescription = stringResource(R.string.element_call_a11y_settings),
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
            CallSettingsMenuItems(
                isDeveloperModeEnabled = state.isDeveloperModeEnabled,
                onOpen = { sheet ->
                    isExpanded = false
                    state.eventSink(ElementCallScreenEvent.ShowSettingsSheet(sheet))
                },
            )
        }
    }
}

@Composable
private fun CallSettingsMenuItems(isDeveloperModeEnabled: Boolean, onOpen: (ElementCallSettingsSheet) -> Unit) {
    CallSettingsMenuItem(
        text = stringResource(R.string.element_call_preferences_title),
        icon = ElementCallTheme.icons.preferences,
        testTag = ElementCallTestTags.PREFERENCES,
        onClick = { onOpen(ElementCallSettingsSheet.PREFERENCES) },
    )
    if (isDeveloperModeEnabled) {
        CallSettingsMenuItem(
            text = stringResource(R.string.element_call_preferences_developer_options),
            icon = ElementCallTheme.icons.developerOptions,
            testTag = ElementCallTestTags.DEVELOPER_OPTIONS,
            onClick = { onOpen(ElementCallSettingsSheet.DEVELOPER_OPTIONS) },
        )
    }
}

@Composable
private fun CallSettingsMenuItem(text: String, icon: ImageVector, testTag: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text = text, color = ElementCallTheme.colors.textPrimary) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = ElementCallTheme.colors.iconSecondary) },
        onClick = onClick,
        modifier = Modifier.testTag(testTag),
    )
}

// The menu itself is a popup, which a screenshot cannot capture, so what it holds is previewed instead;
// the screen previews cover the button in the bar.
@PreviewsDayNight
@Composable
internal fun CallSettingsMenuItemsPreview(@PreviewParameter(DeveloperModeBooleanPreviewParam::class) isDeveloperModeEnabled: Boolean) = ElementCallPreview {
    Column(modifier = Modifier.background(ElementCallTheme.colors.bgSubtlePrimary)) {
        CallSettingsMenuItems(isDeveloperModeEnabled = isDeveloperModeEnabled, onOpen = {})
    }
}

internal class DeveloperModeBooleanPreviewParam : PreviewParameterProvider<Boolean> {
    override val values = sequenceOf(false, true)
}
