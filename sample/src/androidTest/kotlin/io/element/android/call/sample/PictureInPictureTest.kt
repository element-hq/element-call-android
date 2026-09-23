/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import android.app.PictureInPictureParams
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** Hangs up through the controller: nothing in a picture-in-picture window can be tapped. */
@RunWith(AndroidJUnit4::class)
class PictureInPictureTest {
    @Test
    fun aCallThatEndsInPictureInPictureClosesTheWindow() {
        launchSample(SampleFixture.GROUP).use { scenario ->
            scenario.onActivity { it.enterPictureInPictureMode(PictureInPictureParams.Builder().build()) }
            // Not isInPictureInPictureMode: it flips on the request, before the window has opened.
            waitUntil("the window opens") { scenario.isInPictureInPicture() }

            scenario.onActivity { it.controller.hangUp() }

            waitUntil("the window closes") { !scenario.isInPictureInPicture() }
            waitUntil("the activity is stopped, not destroyed") { scenario.state == Lifecycle.State.CREATED }
        }
    }

    private fun ActivityScenario<SampleActivity>.isInPictureInPicture(): Boolean {
        var isInPictureInPicture = false
        onActivity { isInPictureInPicture = it.controller.isInPictureInPicture.value }
        return isInPictureInPicture
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting until $what" }
            SystemClock.sleep(POLL_MS)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 100L
    }
}
