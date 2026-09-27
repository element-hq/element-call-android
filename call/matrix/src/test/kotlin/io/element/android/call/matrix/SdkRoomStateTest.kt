/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.matrix

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.rtc.MatrixRtcEventTypes
import io.element.android.call.api.rtc.id.EventId
import io.element.android.call.api.rtc.id.UserId
import org.junit.Test
import org.matrix.rustcomponents.sdk.RoomStateEvent
import uniffi.ruma_events.StateEventType

class SdkRoomStateTest {
    @Test
    fun `a state event keeps its key, sender, content, id and time, under the type subscribed to`() {
        val event = aRoomStateEvent().toElementCallRoomStateEvent(MatrixRtcEventTypes.MEMBER_ELEMENT_CALL_STATE_UNSTABLE)!!

        assertThat(event.eventType).isEqualTo(MatrixRtcEventTypes.MEMBER_ELEMENT_CALL_STATE_UNSTABLE)
        assertThat(event.stateKey).isEqualTo(A_STATE_KEY)
        assertThat(event.sender).isEqualTo(UserId("@alice:example.org"))
        assertThat(event.contentJson).isEqualTo(A_CONTENT)
        assertThat(event.eventId).isEqualTo(EventId("\$alice1"))
        assertThat(event.timestampMs).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun `stripped state has neither id nor time`() {
        val event = aRoomStateEvent(eventId = null, timestamp = null).toElementCallRoomStateEvent(MatrixRtcEventTypes.MEMBER_ELEMENT_CALL_STATE_UNSTABLE)!!

        assertThat(event.eventId).isNull()
        assertThat(event.timestampMs).isNull()
    }

    @Test
    fun `an event with an invalid sender is dropped`() {
        assertThat(aRoomStateEvent(sender = "alice").toElementCallRoomStateEvent(MatrixRtcEventTypes.MEMBER_ELEMENT_CALL_STATE_UNSTABLE)).isNull()
    }

    private fun aRoomStateEvent(
        sender: String = "@alice:example.org",
        eventId: String? = "\$alice1",
        timestamp: ULong? = 1_700_000_000_000uL,
    ) = RoomStateEvent(
        eventType = StateEventType.CallMember,
        stateKey = A_STATE_KEY,
        sender = sender,
        contentJson = A_CONTENT,
        eventId = eventId,
        timestamp = timestamp,
    )

    private companion object {
        const val A_STATE_KEY = "_@alice:example.org_ALICEDEV_m.call"
        const val A_CONTENT = """{"application":"m.call"}"""
    }
}
