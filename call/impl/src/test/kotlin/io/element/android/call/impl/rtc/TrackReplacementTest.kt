/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TrackReplacementTest {
    @Test
    fun `a replaced track is read again and its old reader cancelled`() = runTest {
        val replacements = MutableStateFlow(0)
        var opens = 0
        var closes = 0
        val frames = readFollowingReplacements(replacements) {
            flow {
                val track = ++opens
                try {
                    emit(track)
                    // A reader on a track that will never send again.
                    awaitCancellation()
                } finally {
                    closes++
                }
            }
        }

        frames.test {
            assertThat(awaitItem()).isEqualTo(1)
            replacements.value++
            assertThat(awaitItem()).isEqualTo(2)
            assertThat(closes).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(closes).isEqualTo(2)
    }

    @Test
    fun `a track that ended waits for its replacement`() = runTest {
        val replacements = MutableStateFlow(0)
        var opens = 0
        val frames = readFollowingReplacements(replacements) { flow { emit(++opens) } }

        frames.test {
            assertThat(awaitItem()).isEqualTo(1)
            expectNoEvents()
            replacements.value++
            assertThat(awaitItem()).isEqualTo(2)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
