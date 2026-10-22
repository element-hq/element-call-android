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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import io.element.android.call.api.rtc.MatrixRtcParticipant
import io.element.android.call.test.FakeElementCallController
import io.element.android.call.tests.testutils.assertNoNodeWithContentDescription
import io.element.android.call.tests.testutils.clickOnContentDescription
import io.element.android.call.tests.testutils.robolectric.RobolectricTest
import org.junit.Test
import org.robolectric.Shadows.shadowOf

private const val HOST_CONTENT = "The host's own screen"

/**
 * What the overlay makes of the snapshot: the bar while minimized, the call screen opened while maximized.
 * Spec rule R9 in test form: the minimized UI is the component's, its placement the host's.
 *
 * Composed in inspection mode, which also keeps the overlay from opening the call screen, except where a
 * test is about that.
 */
class ElementCallOverlayTest : RobolectricTest() {
    @Test
    fun `with no call only the host content is drawn`() = runAndroidComposeUiTest<ComponentActivity> {
        val controller = FakeElementCallController()
        setOverlay(controller)

        onNodeWithText(HOST_CONTENT).assertIsDisplayed()
        assertNoNodeWithContentDescription(R.string.element_call_a11y_hang_up)
    }

    /** The full screen is the call Activity's: the overlay opens it rather than drawing it. */
    @Test
    fun `a maximized call opens the call screen and leaves the host content drawn`() = runAndroidComposeUiTest<ComponentActivity> {
        val controller = FakeElementCallController(initialState = aConnectedCall(isMaximized = true))
        setOverlay(controller, isInspectionMode = false)

        onNodeWithText(HOST_CONTENT).assertIsDisplayed()
        assertNoNodeWithContentDescription(R.string.element_call_a11y_hang_up)
        assertThat(startedActivity()).isEqualTo(ElementCallActivity::class.java.name)
    }

    /** In picture-in-picture the call screen is already open, shrunk, and expanding it is the system's. */
    @Test
    fun `a call in picture-in-picture does not open the call screen`() = runAndroidComposeUiTest<ComponentActivity> {
        val controller = FakeElementCallController(initialState = aConnectedCall(isMaximized = true))
        controller.setInPictureInPicture(true)
        setOverlay(controller, isInspectionMode = false)

        assertThat(startedActivity()).isNull()
    }

    @Test
    fun `a minimized call does not open the call screen`() = runAndroidComposeUiTest<ComponentActivity> {
        val controller = FakeElementCallController(initialState = aConnectedCall(isMaximized = false))
        setOverlay(controller, isInspectionMode = false)

        assertThat(startedActivity()).isNull()
    }

    /** The bar is not a screen to close: back is the host's. */
    @Test
    fun `back on a minimized call reaches the host`() = runAndroidComposeUiTest<ComponentActivity> {
        val controller = FakeElementCallController(initialState = aConnectedCall(isMaximized = false))
        val hostBack = HostBack()
        setOverlay(controller, hostBack)

        runOnUiThread { activity?.onBackPressedDispatcher?.onBackPressed() }
        waitForIdle()

        assertThat(controller.maximizedCalls).isEmpty()
        assertThat(hostBack.count).isEqualTo(1)
    }

    /** The host's content is its whole navigation tree: a call starting, entering PiP or ending must not reset it. */
    @Test
    fun `the host content keeps its state through a call`() = runAndroidComposeUiTest<ComponentActivity> {
        val controller = FakeElementCallController()
        setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                ElementCallOverlay(controller = controller) { modifier ->
                    var taps by remember { mutableIntStateOf(0) }
                    Text(text = "Tapped $taps", modifier = modifier.clickable { taps++ })
                }
            }
        }
        onNodeWithText("Tapped 0").performClick()

        controller.state.value = aConnectedCall(isMaximized = false)
        onNodeWithText("Tapped 1").assertIsDisplayed()

        controller.setInPictureInPicture(true)
        waitForIdle()
        controller.setInPictureInPicture(false)
        onNodeWithText("Tapped 1").assertIsDisplayed()

        controller.state.value = null
        onNodeWithText("Tapped 1").assertIsDisplayed()
    }

    /** A host with no controller until the first call composes the overlay all along, so its content never moves. */
    @Test
    fun `the host content keeps its state when the controller arrives`() = runAndroidComposeUiTest<ComponentActivity> {
        var controller by mutableStateOf<FakeElementCallController?>(null)
        setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                ElementCallOverlay(controller = controller) { modifier ->
                    var taps by remember { mutableIntStateOf(0) }
                    Text(text = "Tapped $taps", modifier = modifier.clickable { taps++ })
                }
            }
        }
        onNodeWithText("Tapped 0").performClick()

        controller = FakeElementCallController(initialState = aConnectedCall(isMaximized = false))

        onNodeWithText("Tapped 1").assertIsDisplayed()
        onNodeWithText("Paulina").assertIsDisplayed()
    }

    /** A voice call docks as a bar above the host's content, and tapping it brings the call back. */
    @Test
    fun `a minimized audio call docks as the bar over the host content`() = runAndroidComposeUiTest<ComponentActivity> {
        val controller = FakeElementCallController(initialState = aConnectedCall(isMaximized = false))
        setOverlay(controller)

        onNodeWithText(HOST_CONTENT).assertIsDisplayed()
        onNodeWithText("Paulina").assertIsDisplayed()

        clickOnContentDescription(R.string.element_call_a11y_mute_microphone)
        onNode(hasText("Paulina") and hasClickAction()).performClick()

        assertThat(controller.microphoneMutedCalls).containsExactly(true)
        assertThat(controller.maximizedCalls).containsExactly(true)
    }

    /** A video call minimizes to the bar too, when picture-in-picture is not there for it. */
    @Test
    fun `a minimized video call docks as the bar`() = runAndroidComposeUiTest<ComponentActivity> {
        val controller = FakeElementCallController(
            initialState = aConnectedCall(isMaximized = false, participants = listOf(aLocalParticipant(), aRemoteCameraParticipant())),
        )
        setOverlay(controller)

        onNodeWithText(HOST_CONTENT).assertIsDisplayed()
        onNode(hasText("Paulina") and hasClickAction()).performClick()

        assertThat(controller.maximizedCalls).containsExactly(true)
    }

    /** Set above every window, so a call that starts minimised has its order by arrival too (019 R7). */
    @Test
    fun `a call sets the small layout's ranking threshold, minimised or not`() = runAndroidComposeUiTest<ComponentActivity> {
        val controller = FakeElementCallController(initialState = aConnectedCall(isMaximized = false))
        setOverlay(controller)
        waitForIdle()

        assertThat(controller.rankingThresholds).containsExactly(SmallCallLayout.RANKING_THRESHOLD)
    }

    /** Minimised, a small call's window follows whoever speaks, and holds them through silence (019 R15). */
    @Test
    fun `minimised, a small call follows its speaker and holds them`() = runAndroidComposeUiTest<ComponentActivity> {
        val participants = listOf(aLocalParticipant(), aRemoteParticipant(), aCrowdParticipant(1))
        val controller = FakeElementCallController(
            initialState = aCallSnapshot(isMaximized = false, participants = participants, speakingIds = setOf(aCrowdMemberId(1)))
                .copy(isMicrophonePermissionGranted = true),
        )
        setOverlay(controller)
        waitForIdle()
        assertThat(controller.detailWindows.last().also.map { it.memberId }).containsExactly(aCrowdMemberId(1))

        controller.state.value = aCallSnapshot(isMaximized = false, participants = participants).copy(isMicrophonePermissionGranted = true)
        waitForIdle()
        assertThat(controller.detailWindows.last().also.map { it.memberId }).containsExactly(aCrowdMemberId(1))
    }

    /** Maximized, the stage is the call screen's to declare, from its own Activity. */
    @Test
    fun `maximized, the overlay declares no detail window`() = runAndroidComposeUiTest<ComponentActivity> {
        val controller = FakeElementCallController(initialState = aConnectedCall(isMaximized = true))
        setOverlay(controller)
        waitForIdle()

        assertThat(controller.detailWindows).isEmpty()
    }

    /** A call minimized before it was granted the microphone is asked for it here, the call screen being closed. */
    @Test
    fun `a microphone permission already held is reported to the controller without asking`() =
        runAndroidComposeUiTest<ComponentActivity> {
            shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
            val controller = FakeElementCallController(
                initialState = aConnectedCall(isMaximized = false).copy(
                    connection = ElementCallConnection.RequestingPermission,
                    isMicrophonePermissionGranted = false,
                ),
            )
            setOverlay(controller)

            assertThat(controller.microphonePermissionAnswers).containsExactly(true)
        }

    /** A host screen's own back handler, as Element X's room screen has. */
    private class HostBack {
        var count = 0
            private set

        fun onBack() {
            count++
        }
    }

    private fun AndroidComposeUiTest<ComponentActivity>.startedActivity(): String? {
        waitForIdle()
        return activity?.let { shadowOf(it).nextStartedActivity?.component?.className }
    }

    private fun AndroidComposeUiTest<ComponentActivity>.setOverlay(
        controller: FakeElementCallController,
        hostBack: HostBack? = null,
        isInspectionMode: Boolean = true,
    ) {
        setContent {
            CompositionLocalProvider(LocalInspectionMode provides isInspectionMode) {
                ElementCallOverlay(controller = controller) { modifier ->
                    Text(text = HOST_CONTENT, modifier = modifier)
                    if (hostBack != null) {
                        BackHandler(onBack = hostBack::onBack)
                    }
                }
            }
        }
    }
}

internal fun aConnectedCall(
    isMaximized: Boolean,
    participants: List<MatrixRtcParticipant> = listOf(aLocalParticipant(), aRemoteParticipant()),
): ElementCallSnapshot = aCallSnapshot(
    connection = ElementCallConnection.Connected,
    roomName = "Paulina",
    connectedAtElapsedMs = 0L,
    isMaximized = isMaximized,
    participants = participants,
).copy(isMicrophonePermissionGranted = true)
