/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class JoinLeaveSoundPolicyTest {
    private val policy = JoinLeaveSoundPolicy(participantCap = 8)

    @Test
    fun `the cap reads the count after the change`() {
        assertThat(policy.shouldPlay(CallSoundEffect.JOIN, participantCount = 9, atMs = 0)).isFalse()
        assertThat(policy.shouldPlay(CallSoundEffect.LEAVE, participantCount = 8, atMs = 0)).isTrue()
        assertThat(policy.shouldPlay(CallSoundEffect.JOIN, participantCount = 8, atMs = 0)).isTrue()
    }

    @Test
    fun `the cap is the host's`() {
        val policy = JoinLeaveSoundPolicy(participantCap = 3)

        assertThat(policy.shouldPlay(CallSoundEffect.JOIN, participantCount = 4, atMs = 0)).isFalse()
        assertThat(policy.shouldPlay(CallSoundEffect.JOIN, participantCount = 3, atMs = 0)).isTrue()
    }

    @Test
    fun `a sound of one kind holds off the same kind for 500 ms`() {
        assertThat(policy.shouldPlay(CallSoundEffect.JOIN, participantCount = 3, atMs = 0)).isTrue()
        assertThat(policy.shouldPlay(CallSoundEffect.JOIN, participantCount = 4, atMs = 300)).isFalse()
        assertThat(policy.shouldPlay(CallSoundEffect.JOIN, participantCount = 5, atMs = 499)).isFalse()
        assertThat(policy.shouldPlay(CallSoundEffect.JOIN, participantCount = 6, atMs = 500)).isTrue()
    }

    @Test
    fun `a join and a leave 200 ms apart both play`() {
        assertThat(policy.shouldPlay(CallSoundEffect.JOIN, participantCount = 3, atMs = 0)).isTrue()
        assertThat(policy.shouldPlay(CallSoundEffect.LEAVE, participantCount = 2, atMs = 200)).isTrue()
    }

    @Test
    fun `several joins at one instant give one sound`() {
        val played = (3..5).count { policy.shouldPlay(CallSoundEffect.JOIN, participantCount = it, atMs = 1_000) }

        assertThat(played).isEqualTo(1)
    }

    @Test
    fun `a capped change opens no window`() {
        assertThat(policy.shouldPlay(CallSoundEffect.LEAVE, participantCount = 9, atMs = 0)).isFalse()
        assertThat(policy.shouldPlay(CallSoundEffect.LEAVE, participantCount = 8, atMs = 100)).isTrue()
    }

    @Test
    fun `nothing plays once silenced`() {
        policy.silence()

        assertThat(policy.shouldPlay(CallSoundEffect.JOIN, participantCount = 2, atMs = 0)).isFalse()
        assertThat(policy.shouldPlay(CallSoundEffect.LEAVE, participantCount = 1, atMs = 10_000)).isFalse()
    }
}
