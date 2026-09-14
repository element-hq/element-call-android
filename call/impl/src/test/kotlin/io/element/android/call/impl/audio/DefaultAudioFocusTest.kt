/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.audio

import android.media.AudioAttributes
import android.media.AudioManager
import androidx.core.content.getSystemService
import com.google.common.truth.Truth.assertThat
import io.element.android.call.tests.testutils.robolectric.RobolectricTest
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

class DefaultAudioFocusTest : RobolectricTest() {
    @Test
    fun `the call requests focus for voice communication`() {
        val context = RuntimeEnvironment.getApplication()
        val audioManager = requireNotNull(context.getSystemService<AudioManager>())

        DefaultAudioFocus(context).requestAudioFocus {}

        val request = requireNotNull(shadowOf(audioManager).lastAudioFocusRequest)
        assertThat(requireNotNull(request.audioFocusRequest).audioAttributes.usage).isEqualTo(AudioAttributes.USAGE_VOICE_COMMUNICATION)
    }

    @Test
    fun `a transient focus loss is reported like a permanent one`() {
        listOf(
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
        ).forEach { focusChange ->
            assertThat(losesFocusOn(focusChange)).isTrue()
        }
    }

    @Test
    fun `gaining focus is not a loss`() {
        assertThat(losesFocusOn(AudioManager.AUDIOFOCUS_GAIN)).isFalse()
    }

    private fun losesFocusOn(focusChange: Int): Boolean {
        val context = RuntimeEnvironment.getApplication()
        val audioManager = requireNotNull(context.getSystemService<AudioManager>())
        var focusLost = false
        DefaultAudioFocus(context).requestAudioFocus { focusLost = true }
        val request = requireNotNull(shadowOf(audioManager).lastAudioFocusRequest)
        request.listener.onAudioFocusChange(focusChange)
        return focusLost
    }
}
