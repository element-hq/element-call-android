/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import android.Manifest
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.element.android.call.ui.R
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The ringing fixtures on the real controller (feature-hq 030), against real touches and the real clock:
 * the ring goes out at 0.5 s and the outcome comes 6 s later. The rules themselves are the unit tests'.
 */
@RunWith(AndroidJUnit4::class)
class RingingTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val hangUp get() = context.getString(R.string.element_call_a11y_hang_up)
    private val calling get() = context.getString(R.string.element_call_calling)

    @Before
    fun grantPermissions() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        automation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
    }

    /** 030 R19: held sideways, where any other stage starts with its chrome away, hang up stays while it rings. */
    @Test
    fun theChromeStaysUpWhileItRingsInLandscape() {
        launchSample(SampleRingingFixture.PICKUP).use { scenario ->
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            composeRule.waitUntil(RING_TIMEOUT_MS) { isShown(calling) }

            composeRule.onRoot().performTouchInput { click(center) }
            Thread.sleep(CHROME_SETTLE_MS)

            check(isShown(hangUp)) { "Hang up went away while ringing" }
        }
    }

    /** 030 R9, R20: Bob picks up, and the call's own screen takes over from "Calling…". */
    @Test
    fun aPickupTurnsTheRingingScreenIntoTheCall() {
        launchSample(SampleRingingFixture.PICKUP).use {
            composeRule.waitUntil(RING_TIMEOUT_MS) { isShown(calling) }
            composeRule.waitUntil(OUTCOME_TIMEOUT_MS) { !isShown(calling) }
            check(composeRule.onAllNodesWithContentDescription("Bob", substring = true).fetchSemanticsNodes().isNotEmpty()) { "Bob's tile is missing" }
        }
    }

    /** 030 R13: a decline closes the call screen at once. */
    @Test
    fun aDeclineClosesTheScreen() {
        launchSample(SampleRingingFixture.DECLINED).use {
            composeRule.waitUntil(RING_TIMEOUT_MS) { isShown(calling) }
            composeRule.waitUntil(OUTCOME_TIMEOUT_MS) { !isShown(hangUp) }
        }
    }

    private fun isShown(text: String) =
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() ||
            composeRule.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        const val RING_TIMEOUT_MS = 5_000L
        const val OUTCOME_TIMEOUT_MS = 10_000L
        const val CHROME_SETTLE_MS = 1_000L
    }
}
