/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.call.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import com.google.common.truth.Truth.assertThat
import io.element.android.call.tests.testutils.EventsRecorder
import io.element.android.call.tests.testutils.assertNodeWithTextIsDisplayed
import io.element.android.call.tests.testutils.clickOn
import io.element.android.call.tests.testutils.clickOnContentDescription
import io.element.android.call.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Test

/**
 * The control bar, driven the way a finger does: each button is one event, and which event depends on
 * what the state says. The events' effect is the controller's business and tested there.
 */
class ElementCallScreenTest : RobolectricTest() {
    @Test
    fun `every control sends its event`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = EventsRecorder<ElementCallScreenEvent>()
        setContent { ElementCallScreen(state = anElementCallScreenState(isScreenShareAvailable = true, eventSink = events)) }
        // The screen says on mount whether TalkBack runs; the buttons are the subject here.
        waitForIdle()
        events.clear()

        clickOnContentDescription(R.string.element_call_a11y_mute_microphone)
        clickOnContentDescription(R.string.element_call_a11y_turn_camera_on)
        clickOnContentDescription(R.string.element_call_a11y_start_screen_share)
        clickOnContentDescription(R.string.element_call_a11y_minimize_call)
        clickOnContentDescription(R.string.element_call_a11y_hang_up)

        events.assertList(
            listOf(
                ElementCallScreenEvent.ToggleMicrophoneMuted,
                ElementCallScreenEvent.ToggleCamera,
                ElementCallScreenEvent.ToggleScreenShare,
                ElementCallScreenEvent.Minimize,
                ElementCallScreenEvent.HangUp,
            )
        )
    }

    /** The buttons are labelled by what tapping them does, so a muted call offers to unmute. */
    @Test
    fun `the labels follow the state`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = EventsRecorder<ElementCallScreenEvent>()
        setContent {
            // Inspection mode: our tile has a picture here, and a real renderer wants a GL context the JVM has none of.
            CompositionLocalProvider(LocalInspectionMode provides true) {
                ElementCallScreen(
                    state = anElementCallScreenState(
                        isMicrophoneMuted = true,
                        isCameraEnabled = true,
                        isScreenShareAvailable = true,
                        isScreenSharing = true,
                        // The switch-camera button is on our own tile, so we need one with a picture.
                        participants = listOf(aLocalParticipant()),
                        videoFrames = mapOf(A_LOCAL_MEMBER_ID to emptyFlow()),
                        eventSink = events,
                    ),
                )
            }
        }

        // The stage declares what it composes and the window it needs on mount; the buttons are the subject here.
        waitForIdle()
        events.clear()
        clickOnContentDescription(R.string.element_call_a11y_unmute_microphone)
        clickOnContentDescription(R.string.element_call_a11y_turn_camera_off)
        clickOnContentDescription(R.string.element_call_a11y_stop_screen_share)
        clickOnContentDescription(R.string.element_call_a11y_switch_camera)

        events.assertList(
            listOf(
                ElementCallScreenEvent.ToggleMicrophoneMuted,
                ElementCallScreenEvent.ToggleCamera,
                ElementCallScreenEvent.ToggleScreenShare,
                ElementCallScreenEvent.SwitchCamera,
            )
        )
    }

    /** Screen sharing is opt-in: a host that has not turned it on gets no button, not a dead one. */
    @Test
    fun `the screen share button is absent unless the host enabled it`() = runAndroidComposeUiTest<ComponentActivity> {
        setContent { ElementCallScreen(state = anElementCallScreenState()) }

        onNodeWithContentDescription(activity!!.getString(R.string.element_call_a11y_start_screen_share)).assertDoesNotExist()
        onNodeWithContentDescription(activity!!.getString(R.string.element_call_a11y_stop_screen_share)).assertDoesNotExist()
    }

    /** The overflow menu says which Element Call this is, and asks the controller for nothing. */
    @Test
    fun `the overflow menu shows the versions`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = EventsRecorder<ElementCallScreenEvent>()
        setContent {
            ElementCallScreen(
                state = anElementCallScreenState(libraryVersion = "1.2.3", coreVersion = "4.5.6", eventSink = events),
            )
        }
        waitForIdle()
        events.clear()

        clickOnContentDescription(R.string.element_call_a11y_more_options)

        onNodeWithText(activity!!.getString(R.string.element_call_version_library, "1.2.3")).assertIsDisplayed()
        onNodeWithText(activity!!.getString(R.string.element_call_version_core, "4.5.6")).assertIsDisplayed()
        events.assertEmpty()
    }

    /** There is no camera to switch while it is off: the button on our tile is only drawn with a picture to turn around. */
    @Test
    fun `switching the camera is not offered while the camera is off`() = runAndroidComposeUiTest<ComponentActivity> {
        setContent { ElementCallScreen(state = anElementCallScreenState(isCameraEnabled = false, participants = listOf(aLocalParticipant()))) }

        onNodeWithContentDescription(activity!!.getString(R.string.element_call_a11y_switch_camera)).assertDoesNotExist()
    }

    @Test
    fun `the audio button opens the picker and choosing a device selects it`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = EventsRecorder<ElementCallScreenEvent>()
        setContent { ElementCallScreen(state = anElementCallScreenState(eventSink = events)) }
        waitForIdle()
        events.clear()

        clickOnContentDescription(R.string.element_call_audio_output_title)
        assertNodeWithTextIsDisplayed(R.string.element_call_audio_output_title)
        clickOn(R.string.element_call_audio_device_speaker)

        events.assertSingle(ElementCallScreenEvent.SelectAudioDevice(A_SPEAKER))
    }

    /** Upright, hiding the chrome takes the control bar and leaves the top bar (spec 014 R30). */
    @Test
    fun `hidden chrome upright is the control bar alone`() = runAndroidComposeUiTest<ComponentActivity> {
        setContent { ElementCallScreen(state = anElementCallScreenState(participants = listOf(aLocalParticipant()), isStageChromeVisible = false)) }

        onNodeWithContentDescription(activity!!.getString(R.string.element_call_a11y_hang_up)).assertDoesNotExist()
        onNodeWithContentDescription(activity!!.getString(R.string.element_call_a11y_minimize_call)).assertIsDisplayed()
    }

    /** One to one the picture is the whole screen, so upright the top bar goes with the control bar too (019 R30). */
    @Test
    fun `hidden chrome one to one upright takes the top bar as well`() = runAndroidComposeUiTest<ComponentActivity> {
        setContent {
            ElementCallScreen(state = anElementCallScreenState(participants = listOf(aLocalParticipant(), aRemoteParticipant()), isStageChromeVisible = false))
        }

        onNodeWithContentDescription(activity!!.getString(R.string.element_call_a11y_hang_up)).assertDoesNotExist()
        onNodeWithContentDescription(activity!!.getString(R.string.element_call_a11y_minimize_call)).assertDoesNotExist()
    }

    /** With three tiles the portrait top bar stays, as 014 R30 has it (019 R30). */
    @Test
    fun `hidden chrome with three upright keeps the top bar`() = runAndroidComposeUiTest<ComponentActivity> {
        setContent {
            ElementCallScreen(
                state = anElementCallScreenState(
                    participants = listOf(aLocalParticipant(), aRemoteParticipant(), aCrowdParticipant(1)),
                    isStageChromeVisible = false,
                ),
            )
        }

        onNodeWithContentDescription(activity!!.getString(R.string.element_call_a11y_minimize_call)).assertIsDisplayed()
    }

    /** In a small call our own tile neither toggles the chrome nor goes fullscreen, and the tap is not the stage's (019 R14, R23). */
    @Test
    fun `in a small call a tap or a double tap on our tile does nothing`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = EventsRecorder<ElementCallScreenEvent>()
        setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                ElementCallScreen(state = anElementCallScreenState(participants = listOf(aLocalParticipant(), aRemoteParticipant()), eventSink = events))
            }
        }
        waitForIdle()
        val own = onNodeWithTag(ElementCallTestTags.tile(A_LOCAL_MEMBER_ID))

        events.clear()
        own.performTouchInput { click() }
        mainClock.advanceTimeBy(1_000)
        own.performTouchInput { doubleClick() }
        waitForIdle()
        assertThat(events.recorded().filter { it is StageChromeEvent.TapStage || it is ElementCallScreenEvent.ToggleFullscreen }).isEmpty()
    }

    /** A tap on a tile is the stage's tap, sent on its touch-up; a double tap is fullscreen and no second tap (014 R14, R16). */
    @Test
    fun `a tap on a tile taps the stage and a double tap goes fullscreen`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = EventsRecorder<ElementCallScreenEvent>()
        setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                ElementCallScreen(state = anElementCallScreenState(participants = listOf(aLocalParticipant(), aRemoteParticipant()), eventSink = events))
            }
        }
        waitForIdle()
        val tile = onNodeWithTag(ElementCallTestTags.tile(A_REMOTE_MEMBER_ID))

        events.clear()
        tile.performTouchInput { click() }
        waitForIdle()
        events.assertList(listOf(StageChromeEvent.TapStage))

        // Past the double-tap timeout, so the next touch is a new gesture rather than the second half of this one.
        mainClock.advanceTimeBy(1_000)
        events.clear()
        tile.performTouchInput { doubleClick() }
        waitForIdle()
        events.assertList(listOf(StageChromeEvent.TapStage, ElementCallScreenEvent.ToggleFullscreen(A_REMOTE_MEMBER_ID)))
    }

    /** A swipe up a grid long enough to scroll is the user's scroll toward the end, and its rest is reported (014 R18, R20). */
    @Test
    fun `a swipe up a long grid reports the scroll and its rest`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = EventsRecorder<ElementCallScreenEvent>()
        setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                ElementCallScreen(
                    state = anElementCallScreenState(
                        participants = listOf(aLocalParticipant(), aRemoteParticipant()) + (1..30).map { aCrowdParticipant(it) },
                        eventSink = events,
                    ),
                )
            }
        }
        waitForIdle()

        events.clear()
        onNodeWithTag(ElementCallTestTags.tile(A_LOCAL_MEMBER_ID)).performTouchInput { swipeUp() }
        waitForIdle()

        val chromeEvents = events.recorded().filterIsInstance<StageChromeEvent>()
        assertThat(chromeEvents.first()).isEqualTo(StageChromeEvent.UserScrolled(towardEnd = true))
        assertThat(chromeEvents.last()).isEqualTo(StageChromeEvent.ScrollIdle)
        assertThat(chromeEvents).doesNotContain(StageChromeEvent.UserScrolled(towardEnd = false))
        assertThat(chromeEvents).doesNotContain(StageChromeEvent.TapStage)
    }

    /** A tap in the gap between two tiles reaches neither and is still the stage's tap (014 R14). */
    @Test
    fun `a tap between the tiles taps the stage`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = EventsRecorder<ElementCallScreenEvent>()
        val hooks = CallStageTestHooks()
        setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalCallStageTestHooks provides hooks) {
                ElementCallScreen(
                    state = anElementCallScreenState(
                        participants = listOf(aLocalParticipant(), aRemoteParticipant()) + (1..2).map { aCrowdParticipant(it) },
                        eventSink = events,
                    ),
                )
            }
        }
        waitForIdle()
        val firstRow = hooks.layout!!.tiles.values.sortedBy { it.top }.take(2).sortedBy { it.left }
        assertThat(firstRow[1].left).isGreaterThan(firstRow[0].right)
        // The stage's grid starts below the top bar: what the root has above the stage's own height.
        val gridTop = onRoot().fetchSemanticsNode().size.height - hooks.stageSize.height
        val gap = Offset((firstRow[0].right + firstRow[1].left) / 2, gridTop + firstRow[0].center.y)

        events.clear()
        onRoot().performTouchInput { click(gap) }
        waitForIdle()
        events.assertList(listOf(StageChromeEvent.TapStage))
    }
}
