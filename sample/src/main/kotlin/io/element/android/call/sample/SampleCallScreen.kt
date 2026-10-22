/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import io.element.android.call.ui.ElementCallStageDriverProvider
import io.element.android.call.ui.theme.ElementCallStyle

/** The call screen, as the sample's [SampleApplication] wraps it: the stage's driver, the style, the scrubber. */
@Composable
fun SampleCallScreen(harness: SampleHarness, modifier: Modifier = Modifier, content: @Composable (style: ElementCallStyle?) -> Unit) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    LaunchedEffect(isLandscape) { harness.controller.fixtures.setStageLandscape(isLandscape) }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(modifier = modifier) {
            ElementCallStageDriverProvider(harness.driver) {
                content(if (harness.isStyleOverridden) rememberLoudElementCallStyle() else null)
            }
            harness.player?.let { ScenarioBar(player = it) }
        }
    }
}
