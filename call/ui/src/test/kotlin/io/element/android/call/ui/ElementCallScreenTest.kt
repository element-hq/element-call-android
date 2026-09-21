/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.call.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import io.element.android.call.tests.testutils.EventsRecorder
import io.element.android.call.tests.testutils.assertNodeWithTextIsDisplayed
import io.element.android.call.tests.testutils.clickOn
import io.element.android.call.tests.testutils.clickOnContentDescription
import io.element.android.call.tests.testutils.robolectric.RobolectricTest
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
            ElementCallScreen(
                state = anElementCallScreenState(
                    isMicrophoneMuted = true,
                    isCameraEnabled = true,
                    isScreenShareAvailable = true,
                    isScreenSharing = true,
                    eventSink = events,
                ),
            )
        }

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

        clickOnContentDescription(R.string.element_call_a11y_more_options)

        onNodeWithText(activity!!.getString(R.string.element_call_version_library, "1.2.3")).assertIsDisplayed()
        onNodeWithText(activity!!.getString(R.string.element_call_version_core, "4.5.6")).assertIsDisplayed()
        events.assertEmpty()
    }

    /** There is no camera to switch while it is off, and a button that does nothing is worse than a disabled one. */
    @Test
    fun `switching the camera is not offered while the camera is off`() = runAndroidComposeUiTest<ComponentActivity> {
        setContent { ElementCallScreen(state = anElementCallScreenState(isCameraEnabled = false)) }

        onNodeWithContentDescription(activity!!.getString(R.string.element_call_a11y_switch_camera)).assertIsNotEnabled()
    }

    @Test
    fun `the audio button opens the picker and choosing a device selects it`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = EventsRecorder<ElementCallScreenEvent>()
        setContent { ElementCallScreen(state = anElementCallScreenState(eventSink = events)) }

        clickOnContentDescription(R.string.element_call_audio_output_title)
        assertNodeWithTextIsDisplayed(R.string.element_call_audio_output_title)
        clickOn(R.string.element_call_audio_device_speaker)

        events.assertSingle(ElementCallScreenEvent.SelectAudioDevice(A_SPEAKER))
    }
}
