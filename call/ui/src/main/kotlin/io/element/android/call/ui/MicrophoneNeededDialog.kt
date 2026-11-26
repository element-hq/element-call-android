/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.element.android.call.ui.preview.ElementCallPreview
import io.element.android.call.ui.preview.PreviewsDayNight
import io.element.android.call.ui.theme.ElementCallTheme

/**
 * Why the lobby cannot join, from a tap on its microphone or on Join (feature-hq 045 R27): 042's title, the lobby's
 * description, and a button that asks the system again where it still asks, else opens the app's settings.
 */
@Composable
internal fun MicrophoneNeededDialog(action: ElementCallLobbyMicrophoneAction, onAction: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onAction, modifier = Modifier.testTag(ElementCallTestTags.LOBBY_MICROPHONE_ACTION)) {
                Text(
                    stringResource(
                        when (action) {
                            ElementCallLobbyMicrophoneAction.ALLOW -> R.string.element_call_lobby_microphone_allow
                            ElementCallLobbyMicrophoneAction.OPEN_SETTINGS -> R.string.element_call_action_open_settings
                        }
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.element_call_action_cancel)) }
        },
        title = { Text(stringResource(R.string.element_call_permission_microphone_title)) },
        text = { Text(stringResource(R.string.element_call_lobby_microphone_description)) },
        containerColor = ElementCallTheme.colors.bgSubtlePrimary,
        titleContentColor = ElementCallTheme.colors.textPrimary,
        textContentColor = ElementCallTheme.colors.textPrimary,
    )
}

@PreviewsDayNight
@Composable
internal fun MicrophoneNeededDialogPreview() = ElementCallPreview {
    MicrophoneNeededDialog(action = ElementCallLobbyMicrophoneAction.ALLOW, onAction = {}, onDismiss = {})
}
