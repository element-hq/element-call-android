/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.element.android.call.ui.ElementCallTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Developer options opened from the settings menu, on a device: a rotation recreates the call screen with a real
 * `ModalBottomSheet` window, which Robolectric does not reproduce faithfully (feature-hq 043 R5, 037 R12a).
 */
@RunWith(AndroidJUnit4::class)
class DeveloperOptionsTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun developerOptionsStayOpenAcrossRotation() {
        launchSample(SampleFixture.MUTED, composeRule).use { scenario ->
            composeRule.onNodeWithTag(ElementCallTestTags.SETTINGS).performClick()
            composeRule.onNodeWithTag(ElementCallTestTags.DEVELOPER_OPTIONS).performClick()
            composeRule.waitForTag(ElementCallTestTags.DEVELOPER_OPTIONS_SHEET) { it }
            // Opening a sheet closes the menu (R5).
            composeRule.waitForTag(ElementCallTestTags.DEVELOPER_OPTIONS) { !it }

            onCallScreen { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            waitUntil("the call screen turns") {
                composeRule.waitForIdle()
                isLandscape()
            }
            composeRule.waitForTag(ElementCallTestTags.DEVELOPER_OPTIONS_SHEET) { it }

            onCallScreen { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            waitUntil("the call screen turns back") {
                composeRule.waitForIdle()
                !isLandscape()
            }
            composeRule.waitForTag(ElementCallTestTags.DEVELOPER_OPTIONS_SHEET) { it }

            // No close button on Android (043 R5): Back closes the sheet, as a swipe or a tap outside would.
            Espresso.pressBack()
            composeRule.waitForTag(ElementCallTestTags.DEVELOPER_OPTIONS_SHEET) { !it }
            scenario.controller().hangUp()
        }
    }

    private fun isLandscape(): Boolean {
        var isLandscape = false
        onCallScreen { isLandscape = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        return isLandscape
    }

    private fun ComposeTestRule.waitForTag(tag: String, isExpected: (Boolean) -> Boolean) {
        waitUntil(timeoutMillis = TIMEOUT_MS) { isExpected(onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
