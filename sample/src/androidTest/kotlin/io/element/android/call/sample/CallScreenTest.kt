/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.Stage
import com.google.common.truth.Truth.assertThat
import io.element.android.call.ui.ElementCallActivity
import io.element.android.call.ui.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The full screen is its own Activity: opened from the bar, left for the host's, closed with the call. */
@RunWith(AndroidJUnit4::class)
class CallScreenTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun aMaximizedCallOpensTheCallScreen() {
        launchSample(SampleFixture.MUTED, composeRule).use {
            awaitActivity<ElementCallActivity>(Stage.RESUMED)
        }
    }

    @Test
    fun minimizingAnAudioCallLeavesForTheAppWithTheBar() {
        launchSample(SampleFixture.MUTED, composeRule).use { scenario ->
            onCallScreen { it.onBackPressedDispatcher.onBackPressed() }

            awaitActivity<SampleActivity>(Stage.RESUMED)
            waitUntil("the call screen is gone") { isCallScreenGone() }
            assertThat(scenario.controller().state.value?.isMaximized).isFalse()
            scenario.controller().hangUp()
        }
    }

    /** What the bar does when tapped: the overlay opens the call screen for a call maximized while it is in front. */
    @Test
    fun maximizingFromTheBarOpensTheCallScreen() {
        launchSample(SampleFixture.MINIMIZED_VOICE, composeRule).use { scenario ->
            composeRule.onNodeWithContentDescription(context.getString(R.string.element_call_a11y_hang_up)).assertExists()
            scenario.controller().setMaximized(true)

            waitUntil("the call screen opens") {
                composeRule.waitForIdle()
                activityIn<ElementCallActivity>(Stage.RESUMED) != null
            }
            scenario.controller().hangUp()
        }
    }

    @Test
    fun hangingUpClosesTheCallScreen() {
        launchSample(SampleFixture.MUTED, composeRule).use {
            awaitActivity<ElementCallActivity>(Stage.RESUMED)

            composeRule.onNodeWithContentDescription(context.getString(R.string.element_call_a11y_hang_up)).performClick()

            waitUntil("the call screen is gone") { isCallScreenGone() }
        }
    }
}

/** The sample's call, the one both of its windows draw. */
internal fun ActivityScenario<SampleActivity>.controller(): SampleCallController {
    var controller: SampleCallController? = null
    onActivity { controller = it.controller }
    return checkNotNull(controller)
}
