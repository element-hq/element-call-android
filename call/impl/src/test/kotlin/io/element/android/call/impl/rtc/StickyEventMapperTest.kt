/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.rtc.id.DeviceId
import io.element.android.call.api.rtc.id.EventId
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.api.rtc.id.UserId
import io.element.android.call.api.matrix.ElementCallEventEncryptionInfo
import io.element.android.call.api.matrix.ElementCallStickyEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import org.junit.Test

private val A_ROOM_ID = RoomId("!aRoom:example.org")
private val A_USER_ID = UserId("@alice:example.org")
private val AN_EVENT_ID = EventId("\$anEvent")

class StickyEventMapperTest {
    @Test
    fun `maps a full join membership`() {
        val result = StickyEventMapper.map(A_ROOM_ID, aStickyEvent())

        assertThat(result).isNotNull()
        result!!
        assertThat(result.roomId).isEqualTo(A_ROOM_ID.value)
        assertThat(result.sender).isEqualTo(A_USER_ID.value)
        assertThat(result.eventType).isEqualTo("m.rtc.member")
        assertThat(result.slotId).isEqualTo("aSlot")
        assertThat(result.stickyKey).isEqualTo("aStickyKey")
        assertThat(result.memberId).isEqualTo("aMemberId")
        assertThat(result.membership).isEqualTo("join")
        assertThat(result.applicationType).isEqualTo("m.call")
        assertThat(result.leaveReason).isNull()
    }

    @Test
    fun `transports are passed through verbatim so the core can parse them`() {
        val result = StickyEventMapper.map(A_ROOM_ID, aStickyEvent())

        val transports = Json.parseToJsonElement(result!!.transportsJson!!) as JsonObject
        assertThat(transports["can_subscribe"]!!.jsonArray).hasSize(1)
        assertThat(transports["published"]!!.jsonArray).hasSize(1)
    }

    @Test
    fun `an encrypted event reports the attested device and encryption`() {
        val result = StickyEventMapper.map(
            A_ROOM_ID,
            aStickyEvent(
                encryptionInfo = ElementCallEventEncryptionInfo(
                    senderId = A_USER_ID,
                    senderDeviceId = DeviceId("ADEVICE"),
                    senderCurve25519Key = "aKey",
                    isSenderCrossSigned = true,
                )
            ),
        )

        assertThat(result!!.wasEncrypted).isTrue()
        assertThat(result.senderDeviceId).isEqualTo("ADEVICE")
    }

    @Test
    fun `a cleartext event reports no device and not encrypted`() {
        val result = StickyEventMapper.map(A_ROOM_ID, aStickyEvent(encryptionInfo = null))

        assertThat(result!!.wasEncrypted).isFalse()
        assertThat(result.senderDeviceId).isNull()
    }

    @Test
    fun `a leave event keeps its reason and needs no transports`() {
        val result = StickyEventMapper.map(
            A_ROOM_ID,
            aStickyEvent(
                content = """
                    {
                      "slot_id": "aSlot",
                      "member": { "id": "aMemberId", "membership": "leave" },
                      "leave_reason": { "code": "m.hangup", "reason": "User hung up" }
                    }
                """.trimIndent()
            ),
        )

        assertThat(result!!.membership).isEqualTo("leave")
        assertThat(result.leaveReason?.code).isEqualTo("m.hangup")
        assertThat(result.leaveReason?.reason).isEqualTo("User hung up")
        assertThat(result.transportsJson).isNull()
    }

    @Test
    fun `a cleartext departure is vouched for, the SDK sends delayed events unencrypted`() {
        // Without this the core discards the dead man's switch leave and the member never goes away.
        val result = StickyEventMapper.map(
            A_ROOM_ID,
            aStickyEvent(
                encryptionInfo = null,
                content = """
                    {
                      "slot_id": "aSlot",
                      "member": { "id": "aMemberId", "membership": "leave" }
                    }
                """.trimIndent(),
            ),
        )

        assertThat(result!!.wasEncrypted).isTrue()
        // Nothing attested one, and we do not invent one.
        assertThat(result.senderDeviceId).isNull()
    }

    @Test
    fun `a cleartext join is not vouched for, only departures are`() {
        val result = StickyEventMapper.map(
            A_ROOM_ID,
            aStickyEvent(
                encryptionInfo = null,
                content = """{ "slot_id": "aSlot", "member": { "id": "aMemberId", "membership": "join" } }""",
            ),
        )

        assertThat(result!!.wasEncrypted).isFalse()
    }

    @Test
    fun `an encrypted departure keeps its attested device`() {
        val result = StickyEventMapper.map(
            A_ROOM_ID,
            aStickyEvent(
                encryptionInfo = ElementCallEventEncryptionInfo(
                    senderId = A_USER_ID,
                    senderDeviceId = DeviceId("ADEVICE"),
                    senderCurve25519Key = "aKey",
                    isSenderCrossSigned = true,
                ),
                content = """{ "slot_id": "aSlot", "member": { "id": "aMemberId", "membership": "leave" } }""",
            ),
        )

        assertThat(result!!.wasEncrypted).isTrue()
        assertThat(result.senderDeviceId).isEqualTo("ADEVICE")
    }

    @Test
    fun `a bare string leave reason is accepted`() {
        val result = StickyEventMapper.map(
            A_ROOM_ID,
            aStickyEvent(content = """{ "slot_id": "aSlot", "leave_reason": "m.hangup" }"""),
        )

        assertThat(result!!.leaveReason?.code).isEqualTo("m.hangup")
        assertThat(result.leaveReason?.reason).isNull()
    }

    @Test
    fun `an event with no slot id is dropped`() {
        val result = StickyEventMapper.map(A_ROOM_ID, aStickyEvent(content = """{ "member": { "id": "x" } }"""))

        assertThat(result).isNull()
    }

    @Test
    fun `an event with no sticky key is dropped`() {
        val result = StickyEventMapper.map(A_ROOM_ID, aStickyEvent(stickyKey = null))

        assertThat(result).isNull()
    }

    @Test
    fun `unparseable json is dropped rather than thrown`() {
        val result = StickyEventMapper.map(A_ROOM_ID, aStickyEvent().copy(eventJson = "not json"))

        assertThat(result).isNull()
    }

    @Test
    fun `the event type is passed through untouched, the core now emits the unstable one`() {
        val result = StickyEventMapper.map(
            A_ROOM_ID,
            aStickyEvent().copy(eventType = "org.matrix.msc4143.rtc.member"),
        )

        assertThat(result!!.eventType).isEqualTo("org.matrix.msc4143.rtc.member")
    }

    @Test
    fun `optional membership fields may all be absent`() {
        val result = StickyEventMapper.map(A_ROOM_ID, aStickyEvent(content = """{ "slot_id": "aSlot" }"""))

        assertThat(result).isNotNull()
        assertThat(result!!.memberId).isNull()
        assertThat(result.membership).isNull()
        assertThat(result.applicationType).isNull()
    }
}

private fun aStickyEvent(
    stickyKey: String? = "aStickyKey",
    encryptionInfo: ElementCallEventEncryptionInfo? = null,
    content: String = """
        {
          "slot_id": "aSlot",
          "msc4354_sticky_key": "aStickyKey",
          "member": { "id": "aMemberId", "membership": "join" },
          "application": { "application_type": "m.call" },
          "transports": {
            "published": [ { "type": "livekit", "livekit_service_url": "https://sfu.example.org/jwt" } ],
            "can_subscribe": [ "livekit" ]
          }
        }
    """.trimIndent(),
) = ElementCallStickyEvent(
    sender = A_USER_ID,
    eventType = "m.rtc.member",
    stickyKey = stickyKey,
    eventId = AN_EVENT_ID,
    expiresAtMs = 0L,
    eventJson = """{ "type": "m.rtc.member", "sender": "${A_USER_ID.value}", "content": $content }""",
    encryptionInfo = encryptionInfo,
)
