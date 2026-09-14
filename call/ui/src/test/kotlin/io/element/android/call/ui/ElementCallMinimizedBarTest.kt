/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.call.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import io.element.android.call.tests.testutils.EnsureCalledOnce
import io.element.android.call.tests.testutils.clickOnContentDescription
import io.element.android.call.tests.testutils.robolectric.RobolectricTest
import org.junit.Test

class ElementCallMinimizedBarTest : RobolectricTest() {
    /** Three things to tap: mute, hang up, and the bar itself to come back to the call. */
    @Test
    fun `the bar's buttons and body each call back once`() = runAndroidComposeUiTest<ComponentActivity> {
        val toggles = EnsureCalledOnce()
        val hangUps = EnsureCalledOnce()
        val expands = EnsureCalledOnce()
        setContent {
            ElementCallMinimizedBar(
                call = aCallSnapshot(roomName = "Paulina"),
                onToggleMicrophone = toggles,
                onHangUp = hangUps,
                onClick = expands,
            )
        }

        clickOnContentDescription(R.string.element_call_a11y_mute_microphone)
        clickOnContentDescription(R.string.element_call_a11y_hang_up)
        onNode(hasText("Paulina") and hasClickAction()).performClick()

        toggles.assertSuccess()
        hangUps.assertSuccess()
        expands.assertSuccess()
    }

    /** A muted call's button offers to unmute: the label is what the tap does, not what the state is. */
    @Test
    fun `a muted call offers to unmute`() = runAndroidComposeUiTest<ComponentActivity> {
        val toggles = EnsureCalledOnce()
        setContent {
            ElementCallMinimizedBar(
                call = aCallSnapshot(isMicrophoneMuted = true),
                onToggleMicrophone = toggles,
                onHangUp = {},
                onClick = {},
            )
        }

        clickOnContentDescription(R.string.element_call_a11y_unmute_microphone)

        toggles.assertSuccess()
    }
}
