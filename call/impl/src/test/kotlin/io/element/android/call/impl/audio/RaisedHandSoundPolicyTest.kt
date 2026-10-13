/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RaisedHandSoundPolicyTest {
    @Test
    fun `a hand going up plays`() {
        assertThat(RaisedHandSoundPolicy().shouldPlay(isOneToOne = false, atMs = 0)).isTrue()
    }

    @Test
    fun `one to one, where no hand is shown, nothing plays`() {
        assertThat(RaisedHandSoundPolicy().shouldPlay(isOneToOne = true, atMs = 0)).isFalse()
    }

    @Test
    fun `a hand raised while the sound plays joins it, and the next one after it plays`() {
        val policy = RaisedHandSoundPolicy()

        assertThat(policy.shouldPlay(isOneToOne = false, atMs = 1_000)).isTrue()
        assertThat(policy.shouldPlay(isOneToOne = false, atMs = 1_000 + RaisedHandSoundPolicy.SOUND_LENGTH_MS - 1)).isFalse()
        assertThat(policy.shouldPlay(isOneToOne = false, atMs = 1_000 + RaisedHandSoundPolicy.SOUND_LENGTH_MS)).isTrue()
    }

    @Test
    fun `a silent hand opens no window`() {
        val policy = RaisedHandSoundPolicy()

        assertThat(policy.shouldPlay(isOneToOne = true, atMs = 0)).isFalse()
        assertThat(policy.shouldPlay(isOneToOne = false, atMs = 1)).isTrue()
    }

    @Test
    fun `nothing plays once silenced`() {
        val policy = RaisedHandSoundPolicy()
        policy.silence()

        assertThat(policy.shouldPlay(isOneToOne = false, atMs = 0)).isFalse()
    }
}
