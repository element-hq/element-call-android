/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.element.android.call.ui.ElementCallOverlay
import kotlinx.collections.immutable.toImmutableList

/**
 * The host, reduced to what a host does: wrap its content in [ElementCallOverlay] and decide the style.
 * Everything below the overlay is the sample's own screen, which the call minimizes over.
 */
@Composable
fun SampleApp(
    controller: SampleElementCallController,
    modifier: Modifier = Modifier,
) {
    var isStyleOverridden by rememberSaveable { mutableStateOf(false) }
    val fixtures = remember { SampleFixture.entries.toImmutableList() }
    MaterialTheme(colorScheme = darkColorScheme()) {
        ElementCallOverlay(
            controller = controller,
            modifier = modifier,
            // Null is the library's defaults; the override is the proof that the port reaches every colour.
            style = if (isStyleOverridden) rememberLoudElementCallStyle() else null,
        ) { contentModifier ->
            SampleHomeScreen(
                fixtures = fixtures,
                isStyleOverridden = isStyleOverridden,
                onToggleStyle = { isStyleOverridden = !isStyleOverridden },
                onOpen = { controller.start(it.snapshot()) },
                modifier = contentModifier,
            )
        }
    }
}
