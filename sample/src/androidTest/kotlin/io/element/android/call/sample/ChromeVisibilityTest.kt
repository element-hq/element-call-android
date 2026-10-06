/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.element.android.call.ui.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * When the stage's chrome shows and hides (spec 014), driven by real touches: whether a tap or a
 * swipe reaches the chrome is the touch pipeline's doing, which only a device arbitrates. The rules
 * themselves are `ElementCallChromeVisibilityTest`'s.
 *
 * Hidden means gone from the tree: the chrome is removed once its slide has finished. Taps are spaced
 * past the double-tap timeout, because a quicker second tap is a double tap.
 */
@RunWith(AndroidJUnit4::class)
class ChromeVisibilityTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun aTapUprightHidesTheControlBarAndKeepsTheTopBar() {
        launchSample(SampleFixture.LISTEN_MODE).use {
            composeRule.waitForChrome { isShown(hangUp) }

            composeRule.onRoot().performTouchInput { click(center) }
            composeRule.waitForChrome { !isShown(hangUp) }
            composeRule.onNodeWithContentDescription(minimize).assertIsDisplayed()

            Thread.sleep(DOUBLE_TAP_GAP_MS)
            composeRule.onRoot().performTouchInput { click(center) }
            composeRule.waitForChrome { isShown(hangUp) }
        }
    }

    @Test
    fun swipingUpHidesTheControlBarAndItComesBackAfterRelease() {
        launchSample(SampleFixture.LISTEN_MODE).use {
            composeRule.waitForChrome { isShown(hangUp) }

            composeRule.onRoot().performTouchInput { swipe(center, center.copy(y = center.y - height / 4), durationMillis = 300) }
            composeRule.waitForChrome { !isShown(hangUp) }

            // Two seconds after the scrolling has come to rest, fling included (R20).
            composeRule.waitForChrome(RETURN_TIMEOUT_MS) { isShown(hangUp) }
        }
    }

    @Test
    fun sidewaysTheChromeStartsHiddenATapShowsItAndUprightItIsBack() {
        launchSample(SampleFixture.LISTEN_MODE).use { scenario ->
            composeRule.waitForChrome { isShown(hangUp) }
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            composeRule.waitForChrome { !isShown(hangUp) && !isShown(minimize) }

            composeRule.showStageChrome()
            composeRule.onNodeWithContentDescription(minimize).assertIsDisplayed()

            composeRule.onRoot().performTouchInput { click(center) }
            composeRule.waitForChrome { !isShown(hangUp) }
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            composeRule.waitForChrome { isShown(hangUp) }
        }
    }

    private fun isShown(label: String) = composeRule.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()

    private val hangUp get() = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.element_call_a11y_hang_up)
    private val minimize get() = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.element_call_a11y_minimize_call)

    private companion object {
        const val DOUBLE_TAP_GAP_MS = 600L
        const val RETURN_TIMEOUT_MS = 5_000L
    }
}
