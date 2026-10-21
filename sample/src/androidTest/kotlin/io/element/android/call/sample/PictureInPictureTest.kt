/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import android.accessibilityservice.AccessibilityService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.Stage
import io.element.android.call.ui.ElementCallActivity
import org.junit.Test
import org.junit.runner.RunWith

/** Picture-in-picture is the call screen's own task shrinking, the host's Activity usable underneath. */
@RunWith(AndroidJUnit4::class)
class PictureInPictureTest {
    /** Out through Home, which shrinks a maximized call on its own; hangs up through the controller. */
    @Test
    fun aCallThatEndsInPictureInPictureClosesTheWindow() {
        launchSample(SampleFixture.ONE_TO_ONE).use { scenario ->
            awaitActivity<ElementCallActivity>(Stage.RESUMED)
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            waitUntil("the window opens") { scenario.controller().isInPictureInPicture.value }

            scenario.controller().hangUp()

            waitUntil("the call screen is gone") { isCallScreenGone() }
        }
    }

    /** Minimizing a video call shrinks it, and the app comes up under the window. */
    @Test
    fun minimizingAVideoCallEntersPictureInPictureOverTheApp() {
        launchSample(SampleFixture.ONE_TO_ONE).use { scenario ->
            onCallScreen { it.onBackPressedDispatcher.onBackPressed() }

            waitUntil("the window opens") { scenario.controller().isInPictureInPicture.value }
            awaitActivity<SampleActivity>(Stage.RESUMED)
            scenario.controller().hangUp()
        }
    }
}
