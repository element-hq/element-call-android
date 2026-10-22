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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.test.FakeElementCallController
import io.element.android.call.tests.testutils.assertNoNodeWithContentDescription
import io.element.android.call.tests.testutils.assertNodeWithContentDescriptionIsDisplayed
import io.element.android.call.tests.testutils.clickOnContentDescription
import io.element.android.call.tests.testutils.robolectric.RobolectricTest
import org.junit.Test
import org.robolectric.Shadows.shadowOf

/**
 * What `ElementCallActivity` draws: the call screen, or the one tile in picture-in-picture, and the
 * Activity's way out through minimize.
 */
class ElementCallWindowTest : RobolectricTest() {
    private var minimizeCount = 0

    @Test
    fun `a maximized call draws the call screen and its controls reach the controller`() = runAndroidComposeUiTest<ComponentActivity> {
        val controller = FakeElementCallController(initialState = aConnectedCall(isMaximized = true))
        setWindow(controller)

        assertNodeWithContentDescriptionIsDisplayed(R.string.element_call_a11y_minimize_call)
        clickOnContentDescription(R.string.element_call_a11y_mute_microphone)
        clickOnContentDescription(R.string.element_call_a11y_hang_up)

        assertThat(controller.microphoneMutedCalls).containsExactly(true)
        assertThat(controller.hangUpCount).isEqualTo(1)
    }

    /** Minimizing is the Activity's to do: it asks the keyguard, then shrinks or leaves for the app. */
    @Test
    fun `the minimize button and back both ask the Activity to minimize`() = runAndroidComposeUiTest<ComponentActivity> {
        val controller = FakeElementCallController(initialState = aConnectedCall(isMaximized = true))
        setWindow(controller)

        clickOnContentDescription(R.string.element_call_a11y_minimize_call)
        runOnUiThread { activity?.onBackPressedDispatcher?.onBackPressed() }
        waitForIdle()

        assertThat(minimizeCount).isEqualTo(2)
        assertThat(controller.maximizedCalls).isEmpty()
    }

    /** Out of the app, the system window shows one tile and no controls. */
    @Test
    fun `in picture-in-picture only the one tile is drawn`() = runAndroidComposeUiTest<ComponentActivity> {
        val controller = FakeElementCallController(initialState = aConnectedCall(isMaximized = true))
        controller.setInPictureInPicture(true)
        setWindow(controller)

        assertNoNodeWithContentDescription(R.string.element_call_a11y_hang_up)
        assertNoNodeWithContentDescription(R.string.element_call_a11y_minimize_call)
    }

    /** Alone, the window shows us rather than an empty "call in progress" (019 R15). */
    @Test
    fun `in picture-in-picture alone, our own tile is shown`() = runAndroidComposeUiTest<ComponentActivity> {
        val controller = FakeElementCallController(initialState = aConnectedCall(isMaximized = true, participants = listOf(aLocalParticipant())))
        controller.setInPictureInPicture(true)
        setWindow(controller)
        waitForIdle()

        assertNoNodeWithContentDescription(R.string.element_call_call_in_progress)
    }

    /** The microphone is the first thing a call needs, and the call screen is where it is asked for. */
    @Test
    fun `a microphone permission already held is reported to the controller without asking`() = runAndroidComposeUiTest<ComponentActivity> {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val controller = FakeElementCallController(
            initialState = aConnectedCall(isMaximized = true).copy(
                connection = ElementCallConnection.RequestingPermission,
                isMicrophonePermissionGranted = false,
            ),
        )
        setWindow(controller)

        assertThat(controller.microphonePermissionAnswers).containsExactly(true)
    }

    private fun AndroidComposeUiTest<ComponentActivity>.setWindow(controller: FakeElementCallController) {
        setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                ElementCallWindow(
                    controller = controller,
                    isInPictureInPicture = controller.isInPictureInPicture.collectAsState().value,
                    onMinimize = { minimizeCount++ },
                    onClose = {},
                )
            }
        }
    }
}
