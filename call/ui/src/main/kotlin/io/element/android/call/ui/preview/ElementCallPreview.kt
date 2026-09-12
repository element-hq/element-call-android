/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Wraps a preview in the theme the call surface uses. The call subtree is always dark, whatever the
 * host's theme, so there is one palette and the day/night pair of [PreviewsDayNight] only varies
 * the surroundings.
 */
@Composable
@Suppress("ModifierMissing")
fun ElementCallPreview(
    showBackground: Boolean = true,
    fillMaxSize: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        val movableContent = remember { movableContentOf { content() } }
        if (showBackground) {
            Surface(
                modifier = if (fillMaxSize) Modifier.fillMaxSize() else Modifier,
                content = movableContent,
            )
        } else if (fillMaxSize) {
            Box(modifier = Modifier.fillMaxSize()) {
                movableContent()
            }
        } else {
            movableContent()
        }
    }
}
