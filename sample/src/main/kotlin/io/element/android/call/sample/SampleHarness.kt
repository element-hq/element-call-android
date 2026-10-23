/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import io.element.android.call.sample.lab.SampleLab
import io.element.android.call.test.scenario.MatrixRtcScenario
import io.element.android.call.ui.ElementCallStageDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * What the sample's two windows share, its home screen and the call screen: the call, the stage's driver,
 * the scenario playing and the style. App-scoped, as a host's call is, so a scenario keeps playing while
 * it minimizes and restores the call.
 */
@Stable
class SampleHarness(context: Context, internal val scope: CoroutineScope) {
    val controller = SampleCallController(context = context, scope = scope)
    val lab = SampleLab(context = context, scope = scope, controller = controller)
    val driver = ElementCallStageDriver()

    var player: SampleScenarioPlayer? by mutableStateOf(null)
        private set

    /** The override is the proof that the style port reaches every colour. */
    var isStyleOverridden: Boolean by mutableStateOf(false)

    /** The Activity in front, which a scenario's `rotate` frame turns: the call screen while it is open. */
    var resumedActivity: Activity? = null

    private var playing: Job? = null

    fun play(scenario: MatrixRtcScenario) {
        stopScenario()
        controller.endLiveCall()
        val player = SampleScenarioPlayer(controller.fixtures, scenario, driver, ::rotate)
        this.player = player
        player.start()
        playing = scope.launch {
            launch { snapshotFlow { player.isPlaying }.collectLatest { if (it) player.play() } }
            // Hanging up ends the scenario with the call.
            controller.state.dropWhile { it == null }.first { it == null }
            this@SampleHarness.player = null
            playing?.cancel()
        }
    }

    fun stopScenario() {
        playing?.cancel()
        playing = null
        player = null
    }

    private fun rotate(isLandscape: Boolean) {
        resumedActivity?.requestedOrientation = if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}
