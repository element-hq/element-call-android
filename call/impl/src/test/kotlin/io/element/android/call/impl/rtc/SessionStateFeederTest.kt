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
import io.element.android.call.test.A_ROOM_ID
import io.element.android.call.test.FakeElementCallMatrixTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionStateFeederTest {
    @Test
    fun `a spec media key delivered by the transport reaches the core`() = runTest {
        val manager = RecordingSessionManager()
        val transport = FakeElementCallMatrixTransport()
        SessionStateFeeder(manager, transport, backgroundScope).start()
        runCurrent()

        transport.givenToDeviceMessage(aSpecKeyMessage())
        runCurrent()

        val key = manager.receivedKeys.single()
        assertThat(key.roomId).isEqualTo(A_ROOM_ID.value)
        assertThat(key.senderUserId).isEqualTo(A_SENDER.value)
        assertThat(key.senderDeviceId).isEqualTo(A_SENDER_DEVICE.value)
        assertThat(key.senderIsCrossSigned).isTrue()
    }

    @Test
    fun `an Element Call media key reaches the core raw, under its attested sender`() = runTest {
        val manager = RecordingSessionManager()
        val transport = FakeElementCallMatrixTransport()
        SessionStateFeeder(manager, transport, backgroundScope).start()
        runCurrent()

        transport.givenToDeviceMessage(aLegacyKeyMessage())
        runCurrent()

        val key = manager.receivedLegacyKeys.single()
        assertThat(key.sender).isEqualTo(A_SENDER.value)
        assertThat(key.contentJson).isEqualTo(A_LEGACY_CONTENT)
        assertThat(key.wasEncrypted).isTrue()
        assertThat(key.senderDeviceId).isEqualTo(A_SENDER_DEVICE.value)
        assertThat(key.senderIsCrossSigned).isTrue()
        assertThat(manager.receivedKeys).isEmpty()
    }

    /**
     * The top-level sender of a cleartext to-device message is unauthenticated: anyone could claim to be
     * a participant and inject a media key.
     */
    @Test
    fun `a cleartext key of either dialect is dropped`() = runTest {
        val manager = RecordingSessionManager()
        val transport = FakeElementCallMatrixTransport()
        SessionStateFeeder(manager, transport, backgroundScope).start()
        runCurrent()

        transport.givenToDeviceMessage(aSpecKeyMessage(encryptionInfo = null))
        transport.givenToDeviceMessage(aLegacyKeyMessage(encryptionInfo = null))
        runCurrent()

        assertThat(manager.receivedKeys).isEmpty()
        assertThat(manager.receivedLegacyKeys).isEmpty()
    }

    /**
     * A to-device message goes to whoever is subscribed when it arrives and is then forgotten: nothing
     * is replayed to a feeder that subscribes later. That is why the feeder subscribes for the whole
     * session, and why a key sent while nothing was subscribed is gone.
     */
    @Test
    fun `a key delivered before the feeder subscribes is not replayed`() = runTest {
        val manager = RecordingSessionManager()
        val transport = FakeElementCallMatrixTransport()
        transport.givenToDeviceMessage(aSpecKeyMessage())

        SessionStateFeeder(manager, transport, backgroundScope).start()
        runCurrent()

        assertThat(manager.receivedKeys).isEmpty()
    }

    private fun aSpecKeyMessage(
        encryptionInfo: ElementCallEventEncryptionInfo? = anEncryptionInfo(),
    ) = ElementCallToDeviceMessage(
        eventType = MatrixRtcEventTypes.ENCRYPTION_KEY,
        senderId = A_SENDER,
        content = """{"room_id":"${A_ROOM_ID.value}","member_id":"aMember","media_key":{"index":1,"key":"c2VjcmV0"}}""",
        encryptionInfo = encryptionInfo,
    )

    private fun aLegacyKeyMessage(
        encryptionInfo: ElementCallEventEncryptionInfo? = anEncryptionInfo(),
    ) = ElementCallToDeviceMessage(
        eventType = MatrixRtcEventTypes.ENCRYPTION_KEY_ELEMENT_CALL,
        senderId = A_SENDER,
        content = A_LEGACY_CONTENT,
        encryptionInfo = encryptionInfo,
    )

    private fun anEncryptionInfo() = ElementCallEventEncryptionInfo(
        senderId = A_SENDER,
        senderDeviceId = A_SENDER_DEVICE,
        senderCurve25519Key = null,
        isSenderCrossSigned = true,
    )

    private companion object {
        val A_SENDER = UserId("@bob:example.org")
        val A_SENDER_DEVICE = DeviceId("BOBDEVICE")
        const val A_LEGACY_CONTENT = """{"keys":[{"index":0,"key":"c2VjcmV0"}],"room_id":"!room:example.org"}"""
    }
}
