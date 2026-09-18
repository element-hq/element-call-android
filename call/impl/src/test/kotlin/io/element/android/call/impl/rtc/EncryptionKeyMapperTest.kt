/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.matrix.ElementCallEventEncryptionInfo
import io.element.android.call.api.matrix.ElementCallToDeviceMessage
import io.element.android.call.api.rtc.MatrixRtcEventTypes
import io.element.android.call.api.rtc.id.DeviceId
import io.element.android.call.api.rtc.id.UserId
import org.junit.Test

private val A_SENDER = UserId("@alice:example.org")
private val AN_IMPOSTOR = UserId("@mallory:example.org")

class EncryptionKeyMapperTest {
    @Test
    fun `maps an encrypted key`() {
        val result = EncryptionKeyMapper.map(aKeyMessage())

        assertThat(result).isNotNull()
        result!!
        assertThat(result.roomId).isEqualTo("!aRoom:example.org")
        assertThat(result.memberId).isEqualTo("aMemberId")
        assertThat(result.keyB64).isEqualTo("aBase64Key")
        assertThat(result.keyIndex).isEqualTo(3.toUByte())
        assertThat(result.wasEncrypted).isTrue()
        assertThat(result.senderIsCrossSigned).isTrue()
    }

    @Test
    fun `the attested sender wins over the claimed one`() {
        // A message can claim any sender at the top level; only the encryption info is attested.
        val result = EncryptionKeyMapper.map(aKeyMessage(claimedSender = AN_IMPOSTOR))

        assertThat(result!!.senderUserId).isEqualTo(A_SENDER.value)
        assertThat(result.senderDeviceId).isEqualTo("ADEVICE")
    }

    @Test
    fun `a cleartext key is rejected`() {
        val result = EncryptionKeyMapper.map(aKeyMessage(encryptionInfo = null))

        assertThat(result).isNull()
    }

    @Test
    fun `a key with no attested sender device is rejected`() {
        val result = EncryptionKeyMapper.map(
            aKeyMessage(
                encryptionInfo = anEncryptionInfo().copy(senderDeviceId = null)
            )
        )

        assertThat(result).isNull()
    }

    @Test
    fun `an unsigned sender is mapped but flagged`() {
        val result = EncryptionKeyMapper.map(
            aKeyMessage(encryptionInfo = anEncryptionInfo().copy(isSenderCrossSigned = false))
        )

        assertThat(result!!.senderIsCrossSigned).isFalse()
    }

    @Test
    fun `content missing a required field is rejected`() {
        val result = EncryptionKeyMapper.map(aKeyMessage(content = """{ "room_id": "!aRoom:example.org" }"""))

        assertThat(result).isNull()
    }

    @Test
    fun `content with the key at the top level rather than in media_key is rejected`() {
        // The shape Element Call's WebView stack sends. Accepting it would hand the core a key it
        // cannot use; better to see it dropped in the log.
        val result = EncryptionKeyMapper.map(
            aKeyMessage(
                content = """
                    {
                      "room_id": "!aRoom:example.org",
                      "member_id": "aMemberId",
                      "key": "aBase64Key",
                      "index": 3
                    }
                """.trimIndent()
            )
        )

        assertThat(result).isNull()
    }

    @Test
    fun `unparseable content is rejected rather than thrown`() {
        val result = EncryptionKeyMapper.map(aKeyMessage(content = "not json"))

        assertThat(result).isNull()
    }

    @Test
    fun `the outgoing key index is read from the same envelope shape`() {
        assertThat(EncryptionKeyMapper.outgoingKeyIndex(A_KEY_CONTENT)).isEqualTo(3)
    }

    /**
     * Every command goes through the logging path, including the ones that carry no media key at all,
     * so an absent index must be silent rather than an error.
     */
    @Test
    fun `content with no media key yields no outgoing index`() {
        assertThat(EncryptionKeyMapper.outgoingKeyIndex("""{"member_id":"aMemberId"}""")).isNull()
        assertThat(EncryptionKeyMapper.outgoingKeyIndex("not json")).isNull()
    }
}

/** The shape the RTC core actually puts on the wire, shared by the inbound and outbound assertions. */
private val A_KEY_CONTENT = """
    {
      "format": 0,
      "media_key": {
        "index": 3,
        "key": "aBase64Key"
      },
      "member_id": "aMemberId",
      "room_id": "!aRoom:example.org"
    }
""".trimIndent()

private fun anEncryptionInfo() = ElementCallEventEncryptionInfo(
    senderId = A_SENDER,
    senderDeviceId = DeviceId("ADEVICE"),
    senderCurve25519Key = "aCurveKey",
    isSenderCrossSigned = true,
)

private fun aKeyMessage(
    claimedSender: UserId = A_SENDER,
    encryptionInfo: ElementCallEventEncryptionInfo? = anEncryptionInfo(),
    content: String = A_KEY_CONTENT,
) = ElementCallToDeviceMessage(
    eventType = MatrixRtcEventTypes.ENCRYPTION_KEY,
    senderId = claimedSender,
    content = content,
    encryptionInfo = encryptionInfo,
)
