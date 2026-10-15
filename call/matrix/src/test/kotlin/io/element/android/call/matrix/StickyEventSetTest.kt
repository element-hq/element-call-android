/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.matrix

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.matrix.ElementCallEventEncryptionInfo
import io.element.android.call.api.rtc.id.DeviceId
import io.element.android.call.api.rtc.id.EventId
import io.element.android.call.api.rtc.id.UserId
import org.junit.Test
import org.matrix.rustcomponents.sdk.EventEncryptionInfo
import org.matrix.rustcomponents.sdk.ShieldState
import org.matrix.rustcomponents.sdk.StickyEvent
import org.matrix.rustcomponents.sdk.StickyEventRemoval
import org.matrix.rustcomponents.sdk.StickyEventsUpdate
import org.matrix.rustcomponents.sdk.StickyKey
import uniffi.matrix_sdk_base.RemovalReason
import uniffi.matrix_sdk_ui.TimelineEventShieldStateCode

class StickyEventSetTest {
    @Test
    fun `a reset is the whole set, even an empty one`() {
        val set = StickyEventSet()

        assertThat(set.apply(StickyEventsUpdate.Reset(emptyList()))).isEmpty()
        val events = set.apply(StickyEventsUpdate.Reset(listOf(aStickyEvent(ALICE, "\$a"), aStickyEvent(BOB, "\$b"))))

        assertThat(events.map { it.eventId }).containsExactly(EventId("\$a"), EventId("\$b")).inOrder()
    }

    @Test
    fun `changes add, replace and remove by key`() {
        val set = StickyEventSet()
        set.apply(StickyEventsUpdate.Reset(listOf(aStickyEvent(ALICE, "\$a1"), aStickyEvent(BOB, "\$b"))))

        val events = set.apply(
            StickyEventsUpdate.Changes(
                added = listOf(aStickyEvent(CAROL, "\$c")),
                updated = listOf(aStickyEvent(ALICE, "\$a2")),
                removed = listOf(StickyEventRemoval(aKey(BOB), RemovalReason.EXPIRED)),
            )
        )

        assertThat(events.map { it.eventId }).containsExactly(EventId("\$a2"), EventId("\$c"))
    }

    @Test
    fun `a later reset drops what it does not list`() {
        val set = StickyEventSet()
        set.apply(StickyEventsUpdate.Reset(listOf(aStickyEvent(ALICE, "\$a"), aStickyEvent(BOB, "\$b"))))

        val events = set.apply(StickyEventsUpdate.Reset(listOf(aStickyEvent(BOB, "\$b"))))

        assertThat(events.map { it.eventId }).containsExactly(EventId("\$b"))
    }

    @Test
    fun `a malformed event is left out, and drops the one it replaced`() {
        val set = StickyEventSet()
        set.apply(StickyEventsUpdate.Reset(listOf(aStickyEvent(ALICE, "\$a"))))

        val events = set.apply(
            StickyEventsUpdate.Changes(added = emptyList(), updated = listOf(aStickyEvent(ALICE, "\$a2", eventJson = "not json")), removed = emptyList())
        )

        assertThat(events).isEmpty()
    }

    @Test
    fun `the event carries its wire type, content and timestamp`() {
        val event = aStickyEvent(ALICE, "\$a").toRoomEvent()!!

        assertThat(event.sender).isEqualTo(UserId(ALICE))
        assertThat(event.eventType).isEqualTo(A_DECLINE_TYPE)
        assertThat(event.stateKey).isNull()
        assertThat(event.timestampMs).isEqualTo(1_000L)
        assertThat(event.contentJson).isEqualTo("""{"m.relates_to":{"rel_type":"m.reference","event_id":"${'$'}ring"}}""")
        assertThat(event.encryptionInfo).isNull()
    }

    @Test
    fun `only a red shield refuses to vouch for the sender`() {
        fun crossSigned(shield: ShieldState) = aStickyEvent(ALICE, "\$a", encryption = anEncryptionInfo(shield)).toRoomEvent()!!.encryptionInfo

        assertThat(crossSigned(ShieldState.None)).isEqualTo(
            ElementCallEventEncryptionInfo(UserId(ALICE), DeviceId("ALICEDEVICE"), "curve", isSenderCrossSigned = true)
        )
        assertThat(crossSigned(ShieldState.Grey(TimelineEventShieldStateCode.UNKNOWN_DEVICE))?.isSenderCrossSigned).isNull()
        assertThat(crossSigned(ShieldState.Red(TimelineEventShieldStateCode.UNSIGNED_DEVICE))?.isSenderCrossSigned).isFalse()
    }

    private fun aKey(sender: String) = StickyKey(sender = sender, eventType = A_DECLINE_TYPE, stickyKey = "key-$sender")

    private fun aStickyEvent(
        sender: String,
        eventId: String,
        eventJson: String = """{"type":"$A_DECLINE_TYPE","sender":"$sender","event_id":"$eventId","origin_server_ts":1000,""" +
            """"content":{"m.relates_to":{"rel_type":"m.reference","event_id":"${'$'}ring"}}}""",
        encryption: EventEncryptionInfo? = null,
    ) = StickyEvent(key = aKey(sender), eventId = eventId, expiresAtMs = 3_600_000uL, eventJson = eventJson, encryptionInfo = encryption)

    private fun anEncryptionInfo(shield: ShieldState) = EventEncryptionInfo(
        senderId = ALICE,
        senderDeviceId = "ALICEDEVICE",
        senderCurve25519Key = "curve",
        sessionId = null,
        shieldState = shield,
        shieldStateStrict = shield,
    )

    private companion object {
        const val ALICE = "@alice:example.org"
        const val BOB = "@bob:example.org"
        const val CAROL = "@carol:example.org"
        const val A_DECLINE_TYPE = "org.matrix.msc4310.rtc.decline"
    }
}
