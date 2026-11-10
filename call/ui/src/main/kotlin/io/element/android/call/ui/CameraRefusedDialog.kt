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
import androidx.compose.ui.res.stringResource
import io.element.android.call.ui.preview.ElementCallPreview
import io.element.android.call.ui.preview.PreviewsDayNight
import io.element.android.call.ui.theme.ElementCallTheme

/** A tap on the camera the system refused without asking: why nothing turned on, and the way to fix it (042 R11). */
@Composable
internal fun CameraRefusedDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.element_call_action_open_settings)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.element_call_action_cancel)) }
        },
        title = { Text(stringResource(R.string.element_call_permission_camera_title)) },
        text = { Text(stringResource(R.string.element_call_permission_camera_description)) },
        containerColor = ElementCallTheme.colors.bgSubtlePrimary,
        titleContentColor = ElementCallTheme.colors.textPrimary,
        textContentColor = ElementCallTheme.colors.textPrimary,
    )
}

@PreviewsDayNight
@Composable
internal fun CameraRefusedDialogPreview() = ElementCallPreview {
    CameraRefusedDialog(onOpenSettings = {}, onDismiss = {})
}
