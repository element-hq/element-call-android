/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.MatrixRtcTileRoster
import io.element.android.call.test.scenario.MatrixRtcScenario
import io.element.android.call.test.scenario.ScenarioAction
import io.element.android.call.test.scenario.ScenarioContent
import io.element.android.call.test.scenario.ScenarioFrame
import io.element.android.call.test.scenario.toRoster
import io.element.android.call.ui.ElementCallStageDriver
import kotlinx.coroutines.delay

/**
 * Plays a layout scenario on a device, under a play bar: the roster and `me` frames go through the
 * sample controller at the file's times, scroll, swipe and fullscreen through the stage's driver,
 * and a rotation through the Activity. `viewport` has no effect on a device.
 * Two phones playing one file should show the same arrangement at each frame; the JVM harness's
 * dumps (`StageDumpScenarioTest`) say which one is wrong.
 */
@Stable
class SampleScenarioPlayer(
    private val controller: SampleElementCallController,
    val scenario: MatrixRtcScenario,
    private val driver: ElementCallStageDriver,
    private val onRotate: (isLandscape: Boolean) -> Unit,
) {
    val durationMs: Long = scenario.frames.lastOrNull()?.timeMs ?: 0L

    var positionMs: Long by mutableLongStateOf(0L)
        private set
    var isPlaying: Boolean by mutableStateOf(false)

    /** How many frames have been applied, of [stepCount]. */
    var step: Int by mutableIntStateOf(0)
        private set
    val stepCount: Int = scenario.frames.size

    /** The last frame applied, as the file writes it after its time: a roster line or an action. */
    val current: String? get() = scenario.frames.getOrNull(step - 1)?.source?.substringAfter(' ', missingDelimiterValue = "")?.trim()

    /** When the last frame applied happens in the file. */
    val currentTimeMs: Long get() = scenario.frames.getOrNull(step - 1)?.timeMs ?: 0L
    private var detailOnly: Set<MatrixRtcTileId>? = null

    /** From the top, playing: the person opened a scenario to watch it. */
    fun start() {
        controller.start(SampleFixture.scenarioBase())
        seekTo(0L)
        isPlaying = true
    }

    /** Pause on the next frame, so a scenario can be walked one frame at a time. */
    fun stepForward() {
        isPlaying = false
        scenario.frames.getOrNull(step)?.let { seekTo(it.timeMs) }
    }

    /** Jump to [ms]. Backwards replays from the start, because a frame is a whole order, not a delta. */
    fun seekTo(ms: Long) {
        val target = ms.coerceIn(0L, durationMs)
        if (target < positionMs) {
            step = 0
            detailOnly = null
            controller.start(SampleFixture.scenarioBase())
        }
        positionMs = target
        while (step < scenario.frames.size && scenario.frames[step].timeMs <= target) {
            apply(scenario.frames[step])
            step++
        }
    }

    suspend fun play() {
        isPlaying = true
        // Play at the end starts again, rather than doing nothing.
        if (positionMs >= durationMs) seekTo(0L)
        while (isPlaying && positionMs < durationMs) {
            delay(TICK_MS)
            seekTo(positionMs + TICK_MS)
        }
        isPlaying = false
    }

    private fun apply(frame: ScenarioFrame) {
        when (val content = frame.content) {
            is ScenarioContent.Roster -> controller.setRoster(content.toRoster(detailOnly))
            is ScenarioContent.Action -> when (val action = content.action) {
                is ScenarioAction.Me -> {
                    controller.setCameraEnabled(action.hasVideo)
                    controller.setMicrophoneMuted(action.isMuted)
                }
                is ScenarioAction.DetailOnly -> {
                    detailOnly = action.tiles.map { it.id }.toSet()
                    controller.setRoster(narrowed(controller.currentRoster(), checkNotNull(detailOnly)))
                }
                ScenarioAction.Minimize -> controller.setMaximized(false)
                ScenarioAction.Restore -> controller.setMaximized(true)
                ScenarioAction.Tick -> Unit
                is ScenarioAction.Scroll -> driver.scrollTo(action.offset)
                is ScenarioAction.SwipeHero -> if (action.next) driver.showNextHero() else driver.showPreviousHero()
                is ScenarioAction.Fullscreen -> {
                    val target = action.tile
                    if (target == null) driver.exitFullscreen() else driver.toggleFullscreen(target.id.memberId, target.isShare)
                }
                is ScenarioAction.Rotate -> onRotate(action.isLandscape)
                is ScenarioAction.Viewport -> Unit
            }
        }
    }

    private fun narrowed(roster: MatrixRtcTileRoster, ids: Set<MatrixRtcTileId>) =
        MatrixRtcTileRoster(order = roster.order, detail = roster.detail.filterKeys { it in ids })

    private companion object {
        const val TICK_MS = 50L
    }
}
