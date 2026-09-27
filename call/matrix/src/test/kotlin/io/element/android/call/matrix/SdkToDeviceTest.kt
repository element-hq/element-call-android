/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.matrix

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.rtc.id.DeviceId
import io.element.android.call.api.rtc.id.UserId
import org.junit.Test
import org.matrix.rustcomponents.sdk.EventEncryptionInfo
import org.matrix.rustcomponents.sdk.ShieldState
import org.matrix.rustcomponents.sdk.ToDeviceMessage
import uniffi.matrix_sdk_ui.TimelineEventShieldStateCode

class SdkToDeviceTest {
    @Test
    fun `an unconfirmed sender is vouched for, a wrong cryptographic story is not`() {
        assertThat(ShieldState.None.vouchesForSender()).isTrue()
        val vouched = TimelineEventShieldStateCode.entries.filter {
            ShieldState.Grey(it).vouchesForSender() && ShieldState.Red(it).vouchesForSender()
        }
        assertThat(vouched).containsExactly(
            TimelineEventShieldStateCode.AUTHENTICITY_NOT_GUARANTEED,
            TimelineEventShieldStateCode.UNKNOWN_DEVICE,
            TimelineEventShieldStateCode.UNVERIFIED_IDENTITY,
        )
    }

    @Test
    fun `an encrypted message carries the attested sender and the sending device`() {
        val message = ToDeviceMessage(
            eventType = A_KEY_TYPE,
            senderId = "@claimed:example.org",
            content = A_CONTENT,
            encryptionInfo = anEncryptionInfo(shieldState = ShieldState.Grey(TimelineEventShieldStateCode.UNKNOWN_DEVICE)),
        ).toElementCallToDeviceMessage()!!

        assertThat(message.senderId).isEqualTo(UserId("@claimed:example.org"))
        assertThat(message.content).isEqualTo(A_CONTENT)
        val info = message.encryptionInfo!!
        assertThat(info.senderId).isEqualTo(ALICE)
        assertThat(info.senderDeviceId).isEqualTo(DeviceId("ALICEDEV"))
        assertThat(info.senderCurve25519Key).isEqualTo("curve-key")
        assertThat(info.isSenderCrossSigned).isTrue()
    }

    @Test
    fun `an unsigned sending device is not vouched for`() {
        val info = anEncryptionInfo(shieldState = ShieldState.Red(TimelineEventShieldStateCode.UNSIGNED_DEVICE)).toElementCallEventEncryptionInfo()!!

        assertThat(info.isSenderCrossSigned).isFalse()
    }

    @Test
    fun `a message in the clear has no encryption info`() {
        val message = ToDeviceMessage(eventType = A_KEY_TYPE, senderId = ALICE.value, content = A_CONTENT, encryptionInfo = null)
            .toElementCallToDeviceMessage()!!

        assertThat(message.encryptionInfo).isNull()
    }

    @Test
    fun `an invalid claimed sender drops the message, an invalid attested one drops the trust`() {
        assertThat(ToDeviceMessage(A_KEY_TYPE, "not-a-user", A_CONTENT, null).toElementCallToDeviceMessage()).isNull()
        assertThat(anEncryptionInfo(senderId = "not-a-user").toElementCallEventEncryptionInfo()).isNull()
    }

    @Test
    fun `recipients sharing a content go in one send, each distinct content in its own`() {
        val grouped = mapOf(
            ALICE to mapOf(DeviceId("A1") to "k1", DeviceId("A2") to "k1"),
            BOB to mapOf(DeviceId("B1") to "k1", DeviceId("B2") to "k2"),
        ).groupedByContent()

        assertThat(grouped).containsExactly(
            "k1",
            mapOf(ALICE.value to listOf("A1", "A2"), BOB.value to listOf("B1")),
            "k2",
            mapOf(BOB.value to listOf("B2")),
        )
    }

    @Test
    fun `failures come back per recipient, without an invalid user id`() {
        val failures = mapOf(ALICE.value to listOf("A1"), "not-a-user" to listOf("X")).toRecipients()

        assertThat(failures).containsExactly(ALICE, listOf(DeviceId("A1")))
    }

    private fun anEncryptionInfo(
        senderId: String = ALICE.value,
        shieldState: ShieldState = ShieldState.None,
    ) = EventEncryptionInfo(
        senderId = senderId,
        senderDeviceId = "ALICEDEV",
        senderCurve25519Key = "curve-key",
        sessionId = null,
        shieldState = shieldState,
        shieldStateStrict = shieldState,
    )

    private companion object {
        const val A_KEY_TYPE = "io.element.call.encryption_keys"
        const val A_CONTENT = """{"keys":[]}"""
        val ALICE = UserId("@alice:example.org")
        val BOB = UserId("@bob:example.org")
    }
}
