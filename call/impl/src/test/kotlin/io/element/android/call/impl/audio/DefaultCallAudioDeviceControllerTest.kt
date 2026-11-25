/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.audio

import android.media.AudioManager
import androidx.core.content.getSystemService
import com.google.common.truth.Truth.assertThat
import io.element.android.call.tests.testutils.robolectric.RobolectricTest
import org.junit.Test
import org.robolectric.RuntimeEnvironment

class DefaultCallAudioDeviceControllerTest : RobolectricTest() {
    private val context = RuntimeEnvironment.getApplication()
    private val audioManager = requireNotNull(context.getSystemService<AudioManager>())

    @Test
    fun `the lobby lists the outputs without taking the communication mode`() {
        audioManager.mode = AudioManager.MODE_NORMAL
        val controller = DefaultCallAudioDeviceController(context)

        controller.start(preferLoudspeaker = true)

        // Android drops the mode a few seconds after it is set with no audio flowing (feature-hq 045 C27).
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_NORMAL)
        controller.stop()
    }

    @Test
    fun `Join takes the mode, and closing gives back the one found`() {
        audioManager.mode = AudioManager.MODE_NORMAL
        val controller = DefaultCallAudioDeviceController(context)
        controller.start(preferLoudspeaker = false)

        controller.startCommunication()
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_IN_COMMUNICATION)
        controller.startCommunication()

        controller.stop()
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_NORMAL)
    }

    @Test
    fun `closing a lobby that never joined leaves the mode alone`() {
        audioManager.mode = AudioManager.MODE_RINGTONE
        val controller = DefaultCallAudioDeviceController(context)
        controller.start(preferLoudspeaker = true)

        controller.stop()

        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_RINGTONE)
    }
}
