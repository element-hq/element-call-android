/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.element.android.call.ui.ElementCallActivity
import io.element.android.call.ui.R

/**
 * Open the sample straight into [fixture], the way `--es fixture` on the launch intent does, and wait for the
 * call screen when the fixture opens maximized. [composeRule], when the test has one, is the clock the
 * sample's composition runs on: nothing is composed, the overlay opening the call screen included, unless
 * the rule is asked to.
 */
internal fun launchSample(fixture: SampleFixture, composeRule: ComposeTestRule? = null): ActivityScenario<SampleActivity> =
    launchSample(fixture.key, isMaximized = fixture.snapshot().isMaximized, composeRule = composeRule)

/** Open the sample straight into a ringing [fixture], on the real controller, and wait for the call screen. */
internal fun launchSample(fixture: SampleRingingFixture, composeRule: ComposeTestRule? = null): ActivityScenario<SampleActivity> =
    launchSample(fixture.key, isMaximized = true, composeRule = composeRule)

private fun launchSample(key: String, isMaximized: Boolean, composeRule: ComposeTestRule?): ActivityScenario<SampleActivity> {
    // The call is app-scoped and outlives a test: the last one is ended first, as a real call ends before the next,
    // or its call screen would close under this one and its memory carry over.
    val harness = ApplicationProvider.getApplicationContext<SampleApplication>().harness
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        harness.stopScenario()
        harness.controller.hangUp()
    }
    waitUntil("the last call screen closes") { isCallScreenGone() }
    val intent = Intent(ApplicationProvider.getApplicationContext(), SampleActivity::class.java)
        .putExtra(SampleActivity.EXTRA_FIXTURE, key)
    val scenario = ActivityScenario.launch<SampleActivity>(intent)
    if (isMaximized) {
        waitUntil("the call screen opens") {
            composeRule?.waitForIdle()
            activityIn<ElementCallActivity>(Stage.RESUMED) != null
        }
        composeRule?.waitForIdle()
    }
    return scenario
}

/** Run [block] on the call screen's Activity once it is in front, on the main thread. */
internal fun onCallScreen(block: (ElementCallActivity) -> Unit) {
    val activity = awaitActivity<ElementCallActivity>(Stage.RESUMED)
    InstrumentationRegistry.getInstrumentation().runOnMainSync { block(activity) }
}

/** Waits for an Activity of type [T] in [stage]. */
internal inline fun <reified T : Activity> awaitActivity(stage: Stage, timeoutMillis: Long = ACTIVITY_TIMEOUT_MS): T {
    val deadline = SystemClock.elapsedRealtime() + timeoutMillis
    while (true) {
        activityIn<T>(stage)?.let { return it }
        check(SystemClock.elapsedRealtime() < deadline) { "No ${T::class.simpleName} in $stage after $timeoutMillis ms" }
        SystemClock.sleep(ACTIVITY_POLL_MS)
    }
}

/** The Activity of type [T] in [stage], if there is one. */
internal inline fun <reified T : Activity> activityIn(stage: Stage): T? {
    var found: T? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).filterIsInstance<T>().firstOrNull()
    }
    return found
}

/** Whether the call screen's Activity is gone, finished and destroyed. */
internal fun isCallScreenGone(): Boolean =
    listOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.RESTARTED)
        .none { activityIn<ElementCallActivity>(it) != null }

/** Polls [condition] in wall-clock time, for what the system does outside the composition. */
internal fun waitUntil(what: String, timeoutMillis: Long = ACTIVITY_TIMEOUT_MS, condition: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMillis
    while (!condition()) {
        check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting until $what" }
        SystemClock.sleep(ACTIVITY_POLL_MS)
    }
}

internal const val ACTIVITY_TIMEOUT_MS = 10_000L
internal const val ACTIVITY_POLL_MS = 100L

/**
 * How many distinct colours a sampled grid of [image] holds, quantised so that compression and
 * dithering do not count. A rendered colour-bar frame has eight; an avatar disc on a flat background
 * has two or three; a black or grey rectangle has one.
 */
internal fun ImageBitmap.distinctColours(grid: Int = COLOUR_GRID): Int {
    val pixels = toPixelMap()
    val colours = HashSet<Int>()
    for (row in 0 until grid) {
        for (column in 0 until grid) {
            val x = (column * (pixels.width - 1)) / (grid - 1)
            val y = (row * (pixels.height - 1)) / (grid - 1)
            val pixel = pixels[x, y]
            colours += ((pixel.red * COLOUR_LEVELS).toInt() shl 8) or ((pixel.green * COLOUR_LEVELS).toInt() shl 4) or (pixel.blue * COLOUR_LEVELS).toInt()
        }
    }
    return colours.size
}

private const val COLOUR_GRID = 24
private const val COLOUR_LEVELS = 7

/**
 * Brings the stage's chrome up where it starts hidden, held sideways (spec 014 R10): a tap on the
 * middle of the stage, then a wait for the control bar, since the tap toggles after a short window.
 */
internal fun ComposeTestRule.showStageChrome() {
    val hangUp = ApplicationProvider.getApplicationContext<Context>().getString(R.string.element_call_a11y_hang_up)
    if (onAllNodesWithContentDescription(hangUp).fetchSemanticsNodes().isNotEmpty()) return
    onRoot().performTouchInput { click(center) }
    waitForChrome { onAllNodesWithContentDescription(hangUp).fetchSemanticsNodes().isNotEmpty() }
    waitForIdle()
}

/**
 * Waits for [condition] while moving the composition's clock on in large steps. The chrome's timers run
 * on that clock, which `waitUntil` moves a frame per poll: too slowly on an emulator to see them fire.
 */
internal fun ComposeTestRule.waitForChrome(timeoutMillis: Long = CHROME_TIMEOUT_MS, condition: () -> Boolean) {
    var elapsed = 0L
    while (!condition()) {
        check(elapsed < timeoutMillis) { "Chrome condition still not satisfied after $timeoutMillis ms of clock time" }
        mainClock.advanceTimeBy(CHROME_CLOCK_STEP_MS)
        elapsed += CHROME_CLOCK_STEP_MS
    }
}

/** Generous against the tap window and the slide, in the composition's clock. */
internal const val CHROME_TIMEOUT_MS = 2_000L
private const val CHROME_CLOCK_STEP_MS = 100L
