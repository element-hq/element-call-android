/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.call.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.element.android.call.tests.testutils.robolectric.RobolectricTest
import org.junit.Test

/** The raised-hand toast and the picker, at the edges a screenshot does not reach. */
class ReactionControlsTest : RobolectricTest() {
    @Test
    fun `a large font cuts the toast's text, never Cancel, which grows with it`() = runAndroidComposeUiTest<ComponentActivity> {
        var width by mutableStateOf(1000.dp)
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                ElementCallRaisedHandBanner(isEnabled = true, onLower = {}, modifier = Modifier.width(width))
            }
        }
        val roomy = onNodeWithTag(ElementCallTestTags.LOWER_HAND).fetchSemanticsNode().size

        width = 160.dp
        waitForIdle()

        val cancel = onNodeWithTag(ElementCallTestTags.LOWER_HAND).fetchSemanticsNode().size
        assertThat(cancel).isEqualTo(roomy)
        onNodeWithTag(ElementCallTestTags.LOWER_HAND).assertHeightIsAtLeast(40.dp)
    }

    @Test
    fun `the picker's reactions are unavailable while our homeserver is unreachable`() = runAndroidComposeUiTest<ComponentActivity> {
        val kind = A_PREVIEW_REACTION_CATALOGUE.first()
        var isEnabled by mutableStateOf(true)
        setContent { ElementCallReactionPickerContent(catalogue = A_PREVIEW_REACTION_CATALOGUE, isEnabled = isEnabled, onSend = {}) }
        onNodeWithTag(ElementCallTestTags.reaction(kind.name)).assertIsEnabled()

        isEnabled = false
        waitForIdle()

        onNodeWithTag(ElementCallTestTags.reaction(kind.name))
            .assertIsNotEnabled()
            .assert(hasStateDescription(activity!!.getString(R.string.element_call_a11y_unavailable)))
    }
}
