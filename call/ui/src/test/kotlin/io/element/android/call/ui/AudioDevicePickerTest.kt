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
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.audio.CallAudioDevice
import io.element.android.call.tests.testutils.EnsureCalledOnce
import io.element.android.call.tests.testutils.EventsRecorder
import io.element.android.call.tests.testutils.clickOn
import io.element.android.call.tests.testutils.robolectric.RobolectricTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test

class AudioDevicePickerTest : RobolectricTest() {
    @Test
    fun `choosing a device selects it and closes the picker`() = runAndroidComposeUiTest<ComponentActivity> {
        val selections = EventsRecorder<CallAudioDevice>()
        val dismissals = EnsureCalledOnce()
        setContent {
            AudioDevicePicker(
                devices = persistentListOf(AN_EARPIECE, A_SPEAKER, A_BLUETOOTH_HEADSET),
                selectedDevice = AN_EARPIECE,
                onSelect = selections,
                onDismiss = dismissals,
            )
        }

        clickOn(R.string.element_call_audio_device_speaker)

        selections.assertSingle(A_SPEAKER)
        dismissals.assertSuccess()
    }

    /** Named hardware is listed under its own name: the type alone is useless when two headsets are paired. */
    @Test
    fun `a headset is listed under its product name`() = runAndroidComposeUiTest<ComponentActivity> {
        val selections = EventsRecorder<CallAudioDevice>()
        setContent {
            AudioDevicePicker(
                devices = persistentListOf(AN_EARPIECE, A_BLUETOOTH_HEADSET),
                selectedDevice = AN_EARPIECE,
                onSelect = selections,
                onDismiss = {},
            )
        }

        onNode(hasText(A_BLUETOOTH_HEADSET.productName!!) and hasClickAction()).performClick()

        selections.assertSingle(A_BLUETOOTH_HEADSET)
    }

    @Test
    fun `the earpiece and the speaker alone toggle, anything else plugged in needs the picker`() {
        assertThat(listOf(AN_EARPIECE, A_SPEAKER).builtInToggleTarget(AN_EARPIECE)).isEqualTo(A_SPEAKER)
        assertThat(listOf(AN_EARPIECE, A_SPEAKER).builtInToggleTarget(A_SPEAKER)).isEqualTo(AN_EARPIECE)
        assertThat(listOf(AN_EARPIECE, A_SPEAKER, A_BLUETOOTH_HEADSET).builtInToggleTarget(AN_EARPIECE)).isNull()
        assertThat(listOf(A_SPEAKER, A_BLUETOOTH_HEADSET).builtInToggleTarget(A_SPEAKER)).isNull()
    }
}
