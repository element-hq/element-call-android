/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity

/** Lets [driver] reach the stage composed in [content]. */
@Composable
fun ElementCallStageDriverProvider(driver: ElementCallStageDriver, content: @Composable () -> Unit) {
    driver.density = LocalDensity.current.density
    CompositionLocalProvider(LocalCallStageTestHooks provides driver.hooks, content = content)
}
