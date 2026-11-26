/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.ElementCallLobbyJoinLabel
import io.element.android.call.tests.testutils.EventsRecorder
import io.element.android.call.tests.testutils.robolectric.RobolectricTest
import org.junit.Test

/** The lobby's screen (feature-hq 045): what a touch and a screen reader reach. */
@OptIn(ExperimentalTestApi::class)
class ElementCallLobbyScreenTest : RobolectricTest() {
    @Test
    fun `a tap, a double tap or a pinch on the preview does nothing, and the switch still switches`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = EventsRecorder<ElementCallScreenEvent>()
        setLobby(aLobbyScreenState(lobby = aReadyLobby(ElementCallLobbyJoinLabel.JOIN, memberCount = 2)), events)
        val preview = onNodeWithTag(ElementCallTestTags.tile(OWN_CAMERA_TILE_ID))

        preview.performTouchInput { click() }
        mainClock.advanceTimeBy(1_000)
        preview.performTouchInput { doubleClick() }
        preview.performTouchInput { pinch(center - Offset(10f, 0f), center - Offset(100f, 0f), center + Offset(10f, 0f), center + Offset(100f, 0f)) }
        waitForIdle()
        assertThat(events.recorded().filter { it is StageChromeEvent.TapStage || it is ElementCallScreenEvent.ToggleFullscreen }).isEmpty()

        onNodeWithContentDescription("Switch camera").performClick()
        assertThat(events.recorded()).contains(ElementCallScreenEvent.SwitchCamera)
    }

    @Test
    fun `Close closes, and Join joins`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = EventsRecorder<ElementCallScreenEvent>()
        setLobby(aLobbyScreenState(lobby = aReadyLobby(ElementCallLobbyJoinLabel.START, memberCount = 0)), events)

        onNodeWithTag(ElementCallTestTags.LOBBY_JOIN).performClick()
        onNodeWithTag(ElementCallTestTags.LOBBY_CLOSE).performClick()

        assertThat(events.recorded().filter { it.isUserAction() }).containsExactly(ElementCallScreenEvent.Join, ElementCallScreenEvent.HangUp).inOrder()
    }

    @Test
    fun `Join is read first, with its label and the count`() = runAndroidComposeUiTest<ComponentActivity> {
        setLobby(aLobbyScreenState(lobby = aReadyLobby(ElementCallLobbyJoinLabel.JOIN, memberCount = 3)))

        onNodeWithTag(ElementCallTestTags.LOBBY_JOIN)
            .assertContentDescriptionEquals("Join call")
            .assert(hasStateDescription("3 people in the call"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, -1f))
    }

    @Test
    fun `a disabled Join says why, loading or with the microphone refused`() = runAndroidComposeUiTest<ComponentActivity> {
        setLobby(aLobbyScreenState(lobby = ElementCallLobbyState.Loading))
        onNodeWithTag(ElementCallTestTags.LOBBY_JOIN)
            .assertIsNotEnabled()
            .assertContentDescriptionEquals("Loading call")
            .assert(hasStateDescription("Loading call"))
    }

    @Test
    fun `a refused microphone blocks Join, and a tap on Join or the microphone says why`() = runAndroidComposeUiTest<ComponentActivity> {
        val refused = aReadyLobby(
            label = ElementCallLobbyJoinLabel.JOIN,
            memberCount = 1,
            camera = ElementCallLobbyCamera.OFF,
            joinUnavailableReason = ElementCallLobbyJoinBlock.MICROPHONE_REFUSED,
            microphoneAction = ElementCallLobbyMicrophoneAction.OPEN_SETTINGS,
        )
        val events = EventsRecorder<ElementCallScreenEvent>()
        setLobby(aLobbyScreenState(lobby = refused, camera = ElementCallLobbyCamera.OFF), events)

        onNodeWithTag(ElementCallTestTags.LOBBY_JOIN)
            .assert(hasStateDescription("Allow access to the microphone to join."))
            .performClick()
        onNodeWithTag(ElementCallTestTags.LOBBY_MICROPHONE_ACTION).performClick()
        onNodeWithContentDescription("Mute microphone").performClick()
        onNodeWithTag(ElementCallTestTags.LOBBY_MICROPHONE_ACTION).assertExists()

        // Never a join, never a mute: only the way to the permission (R27).
        assertThat(events.recorded().filter { it.isUserAction() }).containsExactly(ElementCallScreenEvent.OpenAppSettings)
    }

    @Test
    fun `the preview is announced as our camera, with its state`() = runAndroidComposeUiTest<ComponentActivity> {
        setLobby(aLobbyScreenState(lobby = aReadyLobby(ElementCallLobbyJoinLabel.JOIN, memberCount = 2, camera = ElementCallLobbyCamera.NOT_ALLOWED)))

        onNodeWithTag(ElementCallTestTags.tile(OWN_CAMERA_TILE_ID)).assertContentDescriptionEquals("Your camera, Camera not allowed")
    }

    @Test
    fun `the call-ended notice is announced without moving focus`() = runAndroidComposeUiTest<ComponentActivity> {
        setLobby(aLobbyScreenState(lobby = aReadyLobby(ElementCallLobbyJoinLabel.JOIN_ANYWAY, memberCount = 0, isCallEndedNoticeShown = true)))

        onNodeWithTag(ElementCallTestTags.LOBBY_CALL_ENDED).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
    }

    private fun androidx.compose.ui.test.AndroidComposeUiTest<ComponentActivity>.setLobby(
        state: ElementCallScreenState,
        events: EventsRecorder<ElementCallScreenEvent> = EventsRecorder(),
    ) {
        setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                ElementCallScreen(state = state.copy(eventSink = events))
            }
        }
        waitForIdle()
    }

    /** What a touch sends, apart from the stage's own reports. */
    private fun ElementCallScreenEvent.isUserAction() =
        this !is StageChromeEvent && this !is ElementCallScreenEvent.SetComposedTiles && this !is ElementCallScreenEvent.SetDetailWindow &&
            this !is ElementCallScreenEvent.SetVideoConstraints && this !is ElementCallScreenEvent.SetPictureInPictureSource
}
