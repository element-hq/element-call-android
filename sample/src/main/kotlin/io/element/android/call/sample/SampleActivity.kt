/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import io.element.android.call.impl.ElementCallPictureInPicture
import io.element.android.call.test.scenario.MatrixRtcScenario
import kotlinx.collections.immutable.toImmutableList
import timber.log.Timber
import java.util.zip.ZipFile

/**
 * The one Activity in this repository, and everything a host Activity has to do: bind
 * picture-in-picture both ways, and put [io.element.android.call.ui.ElementCallOverlay] around its
 * content. The manifest carries the other half (`supportsPictureInPicture`, `configChanges`).
 *
 * Which fixture opens first can come from the launch intent, so an instrumented test starts where it
 * means to: `adb shell am start -n io.element.android.call.sample/.SampleActivity --es fixture group`.
 */
class SampleActivity : ComponentActivity() {
    internal lateinit var controller: SampleElementCallController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = SampleElementCallController(scope = lifecycleScope)
        ElementCallPictureInPicture.attach(this, controller)

        val scenarios = listScenarios()
        val requested = intent.getStringExtra(EXTRA_FIXTURE)
        val fixture = requested?.let(SampleFixture::fromKey)
        fixture?.let { controller.start(it.snapshot()) }
        val requestedScenario = intent.getStringExtra(EXTRA_SCENARIO)
        val scenario = requestedScenario?.takeIf { it in scenarios }?.let(MatrixRtcScenario::load)
        // On the home screen rather than only in the log: a test that asked for a fixture that is not
        // there otherwise fails on a missing tile, which is true and about nothing.
        val launchError = when {
            requested != null && fixture == null -> "Unknown fixture '$requested'. Known: ${SampleFixture.entries.joinToString { it.key }}"
            requestedScenario != null && scenario == null -> "Unknown scenario '$requestedScenario'. Known: ${scenarios.joinToString()}"
            else -> null
        }
        launchError?.let { Timber.w("Sample: $it") }

        setContent {
            SampleApp(
                controller = controller,
                scenarios = scenarios.toImmutableList(),
                initialScenario = scenario,
                launchError = launchError,
                // The manifest handles orientation changes itself, so the call and the player survive the turn.
                onRotate = { isLandscape ->
                    requestedOrientation = if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                },
            )
        }
    }

    /**
     * The vendored corpus (`plans/003.call_layout/scenarios/` in feature-hq), listed from the APK's Java
     * resources as the iOS app lists its bundle, so a file added to the corpus shows up without a list to update.
     */
    private fun listScenarios(): List<String> = ZipFile(applicationInfo.sourceDir).use { apk ->
        apk.entries().asSequence()
            .map { it.name }
            .filter { it.startsWith("scenarios/") && it.endsWith(".txt") }
            .map { it.removePrefix("scenarios/").removeSuffix(".txt") }
            .sorted()
            .toList()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        ElementCallPictureInPicture.onUserLeaveHint(this, controller)
    }

    companion object {
        /** A [SampleFixture.key], as a string extra on the launch intent. */
        const val EXTRA_FIXTURE = "fixture"

        /** A scenario's name under `scenarios/`, as a string extra: `--es scenario 004_scroll_and_rank`. */
        const val EXTRA_SCENARIO = "scenario"
    }
}
