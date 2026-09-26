/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)

package io.element.android.call.ui

import android.Manifest
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertWithMessage
import io.element.android.call.api.ElementCallData
import io.element.android.call.api.rtc.MatrixRtcTransport
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.impl.ElementCallStack
import io.element.android.call.test.FakeElementCallLifecycleListener
import io.element.android.call.test.FakeElementCallMatrixTransport
import io.element.android.call.test.FakeElementCallRoomContextProvider
import io.element.android.call.test.FakeMatrixRtcService
import io.element.android.call.test.audio.FakeAudioFocus
import io.element.android.call.test.audio.FakeCallAudioDeviceController
import io.element.android.call.test.scenario.MatrixRtcScenario
import io.element.android.call.test.scenario.ScenarioAction
import io.element.android.call.test.scenario.ScenarioContent
import io.element.android.call.test.scenario.ScenarioFrame
import io.element.android.call.test.scenario.ScriptedMatrixRtcSession
import io.element.android.call.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Ignore
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * The scenario corpus played through the real controller, the real presenter and the real stage,
 * on the JVM, with one clock for the composition and the controller: `mainClock.advanceTimeBy` fires
 * the stage's linger delays and the controller's coroutines together. Each frame is dumped in the
 * README's format and compared with the recorded dump; the same dump from iOS is the parity check.
 *
 * Record with `ELEMENT_CALL_RECORD_DUMPS=1 ./gradlew :call:ui:testDebugUnitTest --tests '*StageDump*'`
 * and review the diff like any other change.
 */
class StageDumpScenarioTest : RobolectricTest() {
    @Test
    fun `001 small calls`() = play("001_small_calls")

    @Test
    fun `002 listen mode`() = play("002_listen_mode")

    @Test
    fun `003 two shares`() = play("003_two_shares")

    @Test
    fun `004 scroll and rank`() = play("004_scroll_and_rank")

    @Ignore("fullscreen is spec 000, enabled by feature/call_fullscreen")
    @Test
    fun `005 rotation and fullscreen`() = play("005_rotation_and_fullscreen")

    @Test
    fun `006 two hundred`() = play("006_two_hundred")

    private fun play(name: String) {
        val scenario = MatrixRtcScenario.load(name)
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        // Cancelled when the scenario ends: the controller samples audio levels on a timer, and the
        // test environment idles the shared scheduler on exit, which a timer never lets it do.
        val controllerJob = SupervisorJob()
        runAndroidComposeUiTest<ComponentActivity>(effectContext = dispatcher) {
            mainClock.autoAdvance = false
            val application = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            val service = FakeMatrixRtcService(transports = listOf(MatrixRtcTransport.LiveKit("https://sfu.example.org/jwt")))
            val stack = ElementCallStack.Builder(application, FakeElementCallMatrixTransport())
                .rtcService(service)
                .audioDeviceController(FakeCallAudioDeviceController())
                .audioFocus(FakeAudioFocus(requestAudioFocusResult = {}, releaseAudioFocusResult = {}))
                .roomContext(FakeElementCallRoomContextProvider())
                .lifecycleListener(FakeElementCallLifecycleListener())
                .build(CoroutineScope(dispatcher + controllerJob + CoroutineExceptionHandler { _, error -> error.printStackTrace() }))
            val controller = stack.controller
            val hooks = CallStageTestHooks()
            var viewport by mutableStateOf(IntSize(DEFAULT_WIDTH, DEFAULT_HEIGHT))
            var chromeHeight by mutableIntStateOf(0)
            hooks.bottomInset = DEFAULT_BOTTOM_INSET.dp
            setContent {
                CompositionLocalProvider(LocalInspectionMode provides true, LocalCallStageTestHooks provides hooks) {
                    // In portrait the top bar sits above the stage, so the box is taller by its
                    // height; in landscape everything floats and the stage is the box.
                    val isLandscape = viewport.width > viewport.height
                    Box(modifier = Modifier.requiredSize(viewport.width.dp, (viewport.height + if (isLandscape) 0 else chromeHeight).dp)) {
                        ElementCallOverlay(controller = controller) { modifier -> Box(modifier) }
                    }
                }
            }
            controller.startCall(ElementCallData(roomId = RoomId("!room:example.com"), isAudioCall = false))
            settle(scheduler)
            controller.setMicrophonePermissionGranted(true)
            controller.setCameraPermissionGranted(true)
            // Joining reads the room context with a timeout; the clock has no origin yet, so let it pass.
            mainClock.advanceTimeBy(JOIN_MS)
            settle(scheduler)
            val session = ScriptedMatrixRtcSession(service)
            session.start()
            settle(scheduler)
            chromeHeight = DEFAULT_HEIGHT - hooks.stageSize.height
            settle(scheduler)

            val dump = StringBuilder()
            var now = 0L
            try {
                scenario.frames.forEach { frame ->
                    advance(frame.timeMs - now, scheduler)
                    now = frame.timeMs
                    apply(frame, session, hooks, controller, viewport) { viewport = it }
                    settle(scheduler)
                    dump.append(CallStageDump.frame(frame.timeMs, frame.source.substringAfter(' ', "").trim(), hooks, session.lastRoster, session.call))
                    dump.append('\n')
                }
            } finally {
                controllerJob.cancel()
                settle(scheduler)
            }
            verify(name, dump.toString())
        }
    }

    private fun AndroidComposeUiTest<ComponentActivity>.apply(
        frame: ScenarioFrame,
        session: ScriptedMatrixRtcSession,
        hooks: CallStageTestHooks,
        controller: io.element.android.call.api.ElementCallController,
        viewport: IntSize,
        setViewport: (IntSize) -> Unit,
    ) {
        when (val content = frame.content) {
            is ScenarioContent.Roster -> session.apply(content)
            is ScenarioContent.Action -> when (val action = content.action) {
                is ScenarioAction.Viewport -> {
                    hooks.bottomInset = action.bottomInset.dp
                    setViewport(IntSize(action.width, action.height))
                }
                is ScenarioAction.Scroll -> runOnUiThread { hooks.scrollTo(action.offset) }
                is ScenarioAction.Rotate -> {
                    val isLandscape = viewport.width > viewport.height
                    if (isLandscape != action.isLandscape) setViewport(IntSize(viewport.height, viewport.width))
                }
                is ScenarioAction.SwipeHero -> runOnUiThread {
                    val shown = hooks.heroes.indexOf(hooks.spotlightTileId)
                    val target = hooks.heroes.getOrNull(if (action.next) shown + 1 else shown - 1)
                    if (target != null) hooks.eventSink(ElementCallScreenEvent.ShowHero(target))
                }
                is ScenarioAction.Me -> {
                    controller.setCameraEnabled(action.hasVideo)
                    controller.setMicrophoneMuted(action.isMuted)
                }
                ScenarioAction.Minimize -> controller.setMaximized(false)
                ScenarioAction.Restore -> controller.setMaximized(true)
                is ScenarioAction.DetailOnly -> session.apply(content)
                is ScenarioAction.Fullscreen -> error("fullscreen is spec 000: feature/call_fullscreen")
                ScenarioAction.Tick -> Unit
            }
        }
    }

    private fun AndroidComposeUiTest<ComponentActivity>.advance(ms: Long, scheduler: TestCoroutineScheduler) {
        if (ms > 0) mainClock.advanceTimeBy(ms)
        settle(scheduler)
    }

    /** Lets the controller's queued work and the composition's effects run, moving the clock by one frame. */
    private fun AndroidComposeUiTest<ComponentActivity>.settle(scheduler: TestCoroutineScheduler) {
        repeat(SETTLE_ROUNDS) {
            scheduler.runCurrent()
            waitForIdle()
            mainClock.advanceTimeByFrame()
        }
        scheduler.runCurrent()
        waitForIdle()
    }

    private fun verify(name: String, actual: String) {
        val recorded = File("src/test/resources/scenarios/$name.dump.txt")
        if (System.getenv("ELEMENT_CALL_RECORD_DUMPS") != null) {
            recorded.parentFile?.mkdirs()
            recorded.writeText(actual)
            return
        }
        assertWithMessage("Recorded dump missing: $recorded").that(recorded.exists()).isTrue()
        val expected = recorded.readText()
        assertWithMessage("Scenario $name differs from its recorded dump. Record with ELEMENT_CALL_RECORD_DUMPS=1 and review the diff.\n$actual")
            .that(actual)
            .isEqualTo(expected)
    }

    private companion object {
        const val DEFAULT_WIDTH = 393
        const val DEFAULT_HEIGHT = 734
        const val DEFAULT_BOTTOM_INSET = 34
        const val JOIN_MS = 2_000L
        const val SETTLE_ROUNDS = 3
    }
}
