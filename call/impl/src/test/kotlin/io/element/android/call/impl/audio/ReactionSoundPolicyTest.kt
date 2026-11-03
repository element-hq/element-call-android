/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReactionSoundPolicyTest {
    @Test
    fun `eight play at once and a ninth plays nothing`() {
        val policy = ReactionSoundPolicy()
        repeat(8) { assertThat(policy.tryStart(atMs = 0, durationMs = 1_000)).isTrue() }

        assertThat(policy.tryStart(atMs = 500, durationMs = 1_000)).isFalse()
        assertThat(policy.playingCount(atMs = 500)).isEqualTo(8)
    }

    @Test
    fun `a voice is free again once its sound has ended`() {
        val policy = ReactionSoundPolicy()
        repeat(7) { policy.tryStart(atMs = 0, durationMs = 5_000) }
        policy.tryStart(atMs = 0, durationMs = 1_000)

        assertThat(policy.tryStart(atMs = 999, durationMs = 1_000)).isFalse()
        assertThat(policy.tryStart(atMs = 1_000, durationMs = 1_000)).isTrue()
    }

    @Test
    fun `however many play, together they never exceed the volume`() {
        (1..ReactionSoundPolicy.MAXIMUM_PLAYING).forEach { playing ->
            assertThat(playing * ReactionSoundPolicy.headroom(playing)).isWithin(1e-6f).of(1f)
        }
    }
}
