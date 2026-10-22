/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.element.android.call.test.scenario.MatrixRtcScenario
import kotlinx.collections.immutable.toImmutableList
import timber.log.Timber
import java.util.zip.ZipFile

/**
 * The host's Activity, and everything a host Activity has to do: put
 * [io.element.android.call.ui.ElementCallOverlay] around its content. The call screen is the library's
 * own Activity; [SampleApplication] is its host.
 *
 * Which fixture opens first can come from the launch intent, so an instrumented test starts where it
 * means to: `adb shell am start -n io.element.android.call.sample/.SampleActivity --es fixture group`.
 */
class SampleActivity : ComponentActivity() {
    private val harness: SampleHarness get() = (application as SampleApplication).harness
    internal val controller: SampleCallController get() = harness.controller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val scenarios = listScenarios()
        val requested = intent.getStringExtra(EXTRA_FIXTURE)
        val fixture = requested?.let(SampleFixture::fromKey)
        fixture?.let { controller.start(it.snapshot()) }
        val ringingFixture = requested?.let(SampleRingingFixture::fromKey)
        ringingFixture?.let { controller.startRinging(it) }
        val requestedScenario = intent.getStringExtra(EXTRA_SCENARIO)
        val scenario = requestedScenario?.takeIf { it in scenarios }?.let(MatrixRtcScenario::load)
        // On the home screen rather than only in the log: a test that asked for a fixture that is not
        // there otherwise fails on a missing tile, which is true and about nothing.
        val launchError = when {
            requested != null && fixture == null && ringingFixture == null ->
                "Unknown fixture '$requested'. Known: ${(SampleFixture.entries.map { it.key } + SampleRingingFixture.entries.map { it.key }).joinToString()}"
            requestedScenario != null && scenario == null -> "Unknown scenario '$requestedScenario'. Known: ${scenarios.joinToString()}"
            else -> null
        }
        launchError?.let { Timber.w("Sample: $it") }
        scenario?.let(harness::play)

        setContent {
            SampleApp(
                harness = harness,
                scenarios = scenarios.toImmutableList(),
                launchError = launchError,
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

    companion object {
        /** A [SampleFixture.key], as a string extra on the launch intent. */
        const val EXTRA_FIXTURE = "fixture"

        /** A scenario's name under `scenarios/`, as a string extra: `--es scenario 004_scroll_and_rank`. */
        const val EXTRA_SCENARIO = "scenario"
    }
}
