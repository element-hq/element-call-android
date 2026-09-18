/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.matrix.rtc.FfiReceiveStats

class ReceiveStatsMapperTest {
    @Test
    fun `counters survive the crossing`() {
        val mapped = anFfiReceiveStats().map()

        assertThat(mapped.packetsReceived).isEqualTo(1_200L)
        assertThat(mapped.packetsLost).isEqualTo(7L)
        assertThat(mapped.bytesReceived).isEqualTo(144_000L)
        assertThat(mapped.jitter).isEqualTo(0.004)
        assertThat(mapped.totalSamplesReceived).isEqualTo(48_000L)
        assertThat(mapped.concealedSamples).isEqualTo(24_000L)
        assertThat(mapped.silentConcealedSamples).isEqualTo(20_000L)
        assertThat(mapped.concealmentEvents).isEqualTo(3L)
    }

    @Test
    fun `a negative packet loss is preserved rather than clamped`() {
        // Duplicates arriving make the transport's delta against expected go negative, and reading
        // that as a huge positive loss would invert what the number means.
        val mapped = anFfiReceiveStats(packetsLost = -4L).map()

        assertThat(mapped.packetsLost).isEqualTo(-4L)
    }

    @Test
    fun `concealment is reported as a fraction of what was played out`() {
        val mapped = anFfiReceiveStats(totalSamplesReceived = 48_000uL, concealedSamples = 12_000uL).map()

        assertThat(mapped.concealedFraction).isEqualTo(0.25f)
    }

    @Test
    fun `concealment is unknown rather than zero before anything has been played out`() {
        val mapped = anFfiReceiveStats(totalSamplesReceived = 0uL, concealedSamples = 0uL).map()

        assertThat(mapped.concealedFraction).isNull()
    }

    private fun anFfiReceiveStats(
        packetsLost: Long = 7L,
        totalSamplesReceived: ULong = 48_000uL,
        concealedSamples: ULong = 24_000uL,
    ) = FfiReceiveStats(
        packetsReceived = 1_200uL,
        packetsLost = packetsLost,
        bytesReceived = 144_000uL,
        jitter = 0.004,
        framesDecoded = 0uL,
        framesDropped = 0uL,
        totalSamplesReceived = totalSamplesReceived,
        concealedSamples = concealedSamples,
        silentConcealedSamples = 20_000uL,
        concealmentEvents = 3uL,
    )
}
