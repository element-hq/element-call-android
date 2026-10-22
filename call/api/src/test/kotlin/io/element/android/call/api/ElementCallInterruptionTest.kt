/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.rtc.MatrixRtcConnectionState
import io.element.android.call.api.rtc.MatrixRtcInterruptions
import io.element.android.call.api.rtc.MatrixRtcSignallingHealth
import org.junit.Test

class ElementCallInterruptionTest {
    private val reachable = MatrixRtcSignallingHealth.Reachable

    @Test
    fun `nothing interrupted is no entry, before media or after`() {
        assertThat(ElementCallInterruption.entries(reachable, null)).isEmpty()
        assertThat(ElementCallInterruption.entries(reachable, MatrixRtcInterruptions.NONE)).isEmpty()
    }

    @Test
    fun `an unreachable homeserver is the server entry before any media`() {
        assertThat(ElementCallInterruption.entries(MatrixRtcSignallingHealth.Unreachable(1), null))
            .containsExactly(ElementCallInterruption.OwnServer(isMembershipLapsed = false))
    }

    @Test
    fun `a lapse is the server entry, even with signalling back`() {
        assertThat(ElementCallInterruption.entries(reachable, MatrixRtcInterruptions.NONE.copy(isOwnMembershipLapsed = true)))
            .containsExactly(ElementCallInterruption.OwnServer(isMembershipLapsed = true))
    }

    @Test
    fun `our first connect is no entry, a reconnect is`() {
        assertThat(ElementCallInterruption.entries(reachable, MatrixRtcInterruptions.NONE.copy(ownMedia = MatrixRtcConnectionState.CONNECTING))).isEmpty()
        assertThat(ElementCallInterruption.entries(reachable, MatrixRtcInterruptions.NONE.copy(ownMedia = MatrixRtcConnectionState.RECONNECTING)))
            .containsExactly(ElementCallInterruption.OwnMedia)
    }

    @Test
    fun `missing keys and unmappable participants are no entry`() {
        val record = MatrixRtcInterruptions.NONE.copy(unableToDecrypt = listOf("bob"), unmappable = listOf("id-erin"))
        assertThat(ElementCallInterruption.entries(reachable, record)).isEmpty()
    }

    @Test
    fun `every cause at once is three entries, in order`() {
        val record = MatrixRtcInterruptions.NONE.copy(
            ownMedia = MatrixRtcConnectionState.UNREACHABLE,
            membersReconnecting = listOf("bob"),
            membersUnreachable = listOf("carol"),
        )
        assertThat(ElementCallInterruption.entries(MatrixRtcSignallingHealth.Unreachable(1), record)).containsExactly(
            ElementCallInterruption.OwnServer(isMembershipLapsed = false),
            ElementCallInterruption.OwnMedia,
            ElementCallInterruption.OthersMedia,
        ).inOrder()
    }
}
