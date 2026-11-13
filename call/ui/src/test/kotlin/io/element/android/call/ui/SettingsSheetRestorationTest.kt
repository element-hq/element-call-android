/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallData
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.test.A_ROOM_ID
import io.element.android.call.test.FakeElementCallController
import io.element.android.call.tests.testutils.robolectric.RobolectricTest
import org.junit.Rule
import org.junit.Test

/** Every settings sheet is still open after the Activity is recreated, as for a rotation (feature-hq 043 R5, 037 R12a). */
class SettingsSheetRestorationTest : RobolectricTest() {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `preferences survive a recreation`() = assertSurvivesRecreation(ElementCallSettingsSheet.PREFERENCES)

    @Test
    fun `developer options survive a recreation`() = assertSurvivesRecreation(ElementCallSettingsSheet.DEVELOPER_OPTIONS)

    private fun assertSurvivesRecreation(sheet: ElementCallSettingsSheet) {
        val controller = FakeElementCallController(
            initialState = ElementCallSnapshot(
                callData = ElementCallData(roomId = A_ROOM_ID, isAudioCall = true, isStartingCall = true),
                connection = ElementCallConnection.Connected,
            ),
        )
        val tester = StateRestorationTester(rule)
        var state: ElementCallScreenState? = null
        tester.setContent { state = rememberElementCallScreenState(controller, NoNavigator) }

        rule.runOnIdle { state!!.eventSink(ElementCallScreenEvent.ShowSettingsSheet(sheet)) }
        rule.runOnIdle { assertThat(state!!.settingsSheet).isEqualTo(sheet) }

        state = null
        tester.emulateSavedInstanceStateRestore()

        rule.runOnIdle { assertThat(state!!.settingsSheet).isEqualTo(sheet) }
    }

    private object NoNavigator : ElementCallNavigator {
        override fun close() = Unit
        override fun minimize() = Unit
        override fun requestCameraPermission() = Unit
        override fun requestScreenCapture() = Unit
        override fun openAppSettings() = Unit
    }
}
