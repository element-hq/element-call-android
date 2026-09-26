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
import androidx.lifecycle.lifecycleScope
import io.element.android.call.impl.ElementCallPictureInPicture
import io.element.android.call.test.scenario.MatrixRtcScenario
import timber.log.Timber

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

        val requested = intent.getStringExtra(EXTRA_FIXTURE)
        if (requested != null) {
            val fixture = SampleFixture.fromKey(requested)
            if (fixture == null) {
                Timber.w("Sample: unknown fixture '$requested', known: ${SampleFixture.entries.map { it.key }}")
            } else {
                controller.start(fixture.snapshot())
            }
        }
        val scenario = intent.getStringExtra(EXTRA_SCENARIO)?.let { name ->
            if (name in SCENARIOS) {
                MatrixRtcScenario.load(name)
            } else {
                Timber.w("Sample: unknown scenario '$name', known: $SCENARIOS")
                null
            }
        }

        setContent {
            SampleApp(controller = controller, initialScenario = scenario)
        }
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

        /** The vendored corpus, `plans/003.call_layout/scenarios/` in feature-hq. */
        val SCENARIOS = listOf(
            "001_small_calls",
            "002_listen_mode",
            "003_two_shares",
            "004_scroll_and_rank",
            "005_rotation_and_fullscreen",
            "006_two_hundred",
        )
    }
}
