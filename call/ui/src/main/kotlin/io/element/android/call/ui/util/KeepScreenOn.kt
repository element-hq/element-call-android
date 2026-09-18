/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/** Holds the window's screen on while composed with [keepScreenOn] true. Copied from Element X's design system. */
@Composable
fun KeepScreenOn(
    keepScreenOn: Boolean = true
) {
    if (keepScreenOn) {
        val currentView = LocalView.current
        DisposableEffect(Unit) {
            currentView.keepScreenOn = true
            onDispose {
                currentView.keepScreenOn = false
            }
        }
    }
}
