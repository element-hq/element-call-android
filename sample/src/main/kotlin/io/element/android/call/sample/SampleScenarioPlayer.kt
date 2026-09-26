/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
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
 * Plays a layout scenario on a device, under a scrubber: the roster and `me` frames go through the
 * sample controller at the file's times, scroll, swipe and fullscreen through the stage's driver,
 * and a rotation through the Activity. `viewport` has no effect on a device and is shown as a cue.
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

    /** The last action frame applied, or one that has no effect on a device. */
    var cue: String? by mutableStateOf(null)
        private set

    private var applied = 0
    private var detailOnly: Set<MatrixRtcTileId>? = null

    fun start() {
        controller.start(SampleFixture.scenarioBase())
        seekTo(0L)
    }

    /** Jump to [ms]. Backwards replays from the start, because a frame is a whole order, not a delta. */
    fun seekTo(ms: Long) {
        val target = ms.coerceIn(0L, durationMs)
        if (target < positionMs) {
            applied = 0
            detailOnly = null
            cue = null
            controller.start(SampleFixture.scenarioBase())
        }
        positionMs = target
        while (applied < scenario.frames.size && scenario.frames[applied].timeMs <= target) {
            apply(scenario.frames[applied])
            applied++
        }
    }

    suspend fun play() {
        isPlaying = true
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
                is ScenarioAction.Scroll -> {
                    driver.scrollTo(action.offset)
                    cue = frame.source
                }
                is ScenarioAction.SwipeHero -> {
                    if (action.next) driver.showNextHero() else driver.showPreviousHero()
                    cue = frame.source
                }
                is ScenarioAction.Fullscreen -> {
                    val target = action.tile
                    if (target == null) driver.exitFullscreen() else driver.toggleFullscreen(target.id.memberId, target.isShare)
                    cue = frame.source
                }
                is ScenarioAction.Rotate -> {
                    onRotate(action.isLandscape)
                    cue = frame.source
                }
                is ScenarioAction.Viewport -> cue = frame.source
            }
        }
    }

    private fun narrowed(roster: MatrixRtcTileRoster, ids: Set<MatrixRtcTileId>) =
        MatrixRtcTileRoster(order = roster.order, detail = roster.detail.filterKeys { it in ids })

    private companion object {
        const val TICK_MS = 50L
    }
}
