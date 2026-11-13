/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.ElementCallFailure
import io.element.android.call.api.rtc.MatrixRtcConnectionLossReason
import io.element.android.call.api.rtc.MatrixRtcMembershipFormat
import org.junit.Test

/** Which failures say the membership format may be their cause (feature-hq 043 R18, C4). */
class MayBeMembershipFormatTest {
    private val joinFailures = listOf(
        ElementCallFailure.JoinRefused,
        // The open-questions Q1 choice: a timed-out join names it too.
        ElementCallFailure.JoinTimedOut,
        // The room open, any other join error and the media connection during the join.
        ElementCallFailure.Other("Join failed: no LiveKit transport of type m.livekit"),
    )

    @Test
    fun `every join failure in MSC may be the format`() {
        joinFailures.forEach { assertThat(it.mayBeMembershipFormat(MatrixRtcMembershipFormat.MSC)).isTrue() }
    }

    @Test
    fun `after the join only the lost token endpoint in MSC may be the format`() {
        MatrixRtcConnectionLossReason.entries.forEach { reason ->
            val expected = reason == MatrixRtcConnectionLossReason.TOKEN_ENDPOINT_UNSUPPORTED
            assertThat(ElementCallFailure.ConnectionLost(reason).mayBeMembershipFormat(MatrixRtcMembershipFormat.MSC)).isEqualTo(expected)
        }
    }

    @Test
    fun `a refused microphone is never the format`() {
        assertThat(ElementCallFailure.MicrophoneRefused.mayBeMembershipFormat(MatrixRtcMembershipFormat.MSC)).isFalse()
    }

    @Test
    fun `nothing in room state, or before the join started, is the format`() {
        val all = joinFailures + MatrixRtcConnectionLossReason.entries.map(ElementCallFailure::ConnectionLost) + ElementCallFailure.MicrophoneRefused
        listOf(MatrixRtcMembershipFormat.ROOM_STATE, null).forEach { format ->
            all.forEach { assertThat(it.mayBeMembershipFormat(format)).isFalse() }
        }
    }
}
