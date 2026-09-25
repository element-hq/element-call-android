/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.call.ui

import android.Manifest
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.test.FakeElementCallController
import io.element.android.call.tests.testutils.assertNoNodeWithContentDescription
import io.element.android.call.tests.testutils.assertNodeWithContentDescriptionIsDisplayed
import io.element.android.call.tests.testutils.clickOnContentDescription
import io.element.android.call.tests.testutils.robolectric.RobolectricTest
import org.junit.Test
import org.robolectric.Shadows.shadowOf

private const val HOST_CONTENT = "The host's own screen"

/**
 * Which rendering of the call the overlay picks from the snapshot, and that each of them reaches the
 * controller. Spec rule R9 in test form: the minimized UI is the component's, its placement the host's.
 *
 * Composed in inspection mode so a tile with video draws the renderer's placeholder rather than a GL
 * surface, which the JVM has none of.
 */
class ElementCallOverlayTest : RobolectricTest() {
    @Test
    fun `with no call only the host content is drawn`() = runAndroidComposeUiTest<ComponentActivity> {
        val controller = FakeElementCallController()
        setOverlay(controller)

        onNodeWithText(HOST_CONTENT).assertIsDisplayed()
        assertNoNodeWithContentDescription(R.string.element_call_a11y_hang_up)
    }

    @Test
    fun `a maximized call covers the host with the call screen and its controls reach the controller`() =
        runAndroidComposeUiTest<ComponentActivity> {
            val controller = FakeElementCallController(initialState = aConnectedSnapshot(isMaximized = true))
            setOverlay(controller)

            assertNodeWithContentDescriptionIsDisplayed(R.string.element_call_a11y_minimize_call)
            clickOnContentDescription(R.string.element_call_a11y_mute_microphone)
            clickOnContentDescription(R.string.element_call_a11y_hang_up)

            assertThat(controller.microphoneMutedCalls).containsExactly(true)
            assertThat(controller.hangUpCount).isEqualTo(1)
        }

    @Test
    fun `a maximized call that ends gives the screen back to the host`() = runAndroidComposeUiTest<ComponentActivity> {
        val controller = FakeElementCallController(initialState = aConnectedSnapshot(isMaximized = true))
        setOverlay(controller)
        assertNodeWithContentDescriptionIsDisplayed(R.string.element_call_a11y_hang_up)

        controller.state.value = null

        onNodeWithText(HOST_CONTENT).assertIsDisplayed()
        assertNoNodeWithContentDescription(R.string.element_call_a11y_hang_up)
    }

    /** Back minimizes a maximized call instead of reaching the host's navigation underneath. */
    @Test
    fun `back on a maximized call minimizes it`() = runAndroidComposeUiTest<ComponentActivity> {
        val controller = FakeElementCallController(initialState = aConnectedSnapshot(isMaximized = true))
        setOverlay(controller)

        pressBack()

        assertThat(controller.maximizedCalls).containsExactly(false)
        assertThat(activity?.isFinishing).isFalse()
    }

    /** Minimized, the call leaves back to the host: the bar is not a screen to close. */
    @Test
    fun `back on a minimized call reaches the host`() = runAndroidComposeUiTest<ComponentActivity> {
        val controller = FakeElementCallController(initialState = aConnectedSnapshot(isMaximized = false))
        setOverlay(controller)

        pressBack()

        assertThat(controller.maximizedCalls).isEmpty()
        assertThat(activity?.isFinishing).isTrue()
    }

    /** A voice call docks as a bar above the host's content, and tapping it brings the call back. */
    @Test
    fun `a minimized audio call docks as the bar over the host content`() = runAndroidComposeUiTest<ComponentActivity> {
        val controller = FakeElementCallController(initialState = aConnectedSnapshot(isMaximized = false))
        setOverlay(controller)

        onNodeWithText(HOST_CONTENT).assertIsDisplayed()
        onNodeWithText("Paulina").assertIsDisplayed()
        // The bar, not the full screen: no camera button anywhere.
        assertNoNodeWithContentDescription(R.string.element_call_a11y_turn_camera_on)

        clickOnContentDescription(R.string.element_call_a11y_mute_microphone)
        onNode(hasText("Paulina") and hasClickAction()).performClick()

        assertThat(controller.microphoneMutedCalls).containsExactly(true)
        assertThat(controller.maximizedCalls).containsExactly(true)
    }

    /** A video call floats as a tile instead: a 56dp strip is no way to show a picture. */
    @Test
    fun `a minimized video call floats as a tile instead of the bar`() = runAndroidComposeUiTest<ComponentActivity> {
        val controller = FakeElementCallController(
            initialState = aConnectedSnapshot(isMaximized = false, participants = listOf(aLocalParticipant(), aRemoteCameraParticipant())),
        )
        setOverlay(controller)

        onNodeWithText(HOST_CONTENT).assertIsDisplayed()
        // No bar, so no room name; the tile shows the renderer's inspection placeholder.
        onNodeWithText("Paulina").assertDoesNotExist()
        onNode(hasText("video") and hasClickAction()).performClick()

        assertThat(controller.maximizedCalls).containsExactly(true)
    }

    /** Out of the app, the system window shows one tile and nothing of the host. */
    @Test
    fun `in picture-in-picture only the one tile is drawn`() = runAndroidComposeUiTest<ComponentActivity> {
        val controller = FakeElementCallController(initialState = aConnectedSnapshot(isMaximized = true))
        controller.setInPictureInPicture(true)
        setOverlay(controller)

        onNodeWithText(HOST_CONTENT).assertDoesNotExist()
        assertNoNodeWithContentDescription(R.string.element_call_a11y_hang_up)
        assertNoNodeWithContentDescription(R.string.element_call_a11y_minimize_call)
    }

    /**
     * The microphone is the first thing a call needs and only an Activity can ask for it, so the overlay
     * asks. A permission the app already holds is answered at once, without a dialog.
     */
    @Test
    fun `a microphone permission already held is reported to the controller without asking`() =
        runAndroidComposeUiTest<ComponentActivity> {
            shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
            val controller = FakeElementCallController(
                initialState = aConnectedSnapshot(isMaximized = true).copy(
                    connection = ElementCallConnection.RequestingPermission,
                    isMicrophonePermissionGranted = false,
                ),
            )
            setOverlay(controller)

            assertThat(controller.microphonePermissionAnswers).containsExactly(true)
        }

    private fun AndroidComposeUiTest<ComponentActivity>.pressBack() {
        runOnUiThread { activity?.onBackPressedDispatcher?.onBackPressed() }
        waitForIdle()
    }

    private fun AndroidComposeUiTest<ComponentActivity>.setOverlay(controller: FakeElementCallController) {
        setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                ElementCallOverlay(controller = controller) { modifier ->
                    Text(text = HOST_CONTENT, modifier = modifier)
                }
            }
        }
    }

    private fun aConnectedSnapshot(
        isMaximized: Boolean,
        participants: List<io.element.android.call.api.rtc.MatrixRtcParticipant> = listOf(aLocalParticipant(), aRemoteParticipant()),
    ): ElementCallSnapshot = aCallSnapshot(
        connection = ElementCallConnection.Connected,
        roomName = "Paulina",
        connectedAtElapsedMs = 0L,
        isMaximized = isMaximized,
        participants = participants,
    ).copy(isMicrophonePermissionGranted = true)
}
