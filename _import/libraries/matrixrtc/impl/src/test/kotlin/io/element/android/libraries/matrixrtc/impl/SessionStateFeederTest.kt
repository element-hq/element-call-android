/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrixrtc.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.DeviceId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrixrtc.api.MatrixRtcEventTypes
import io.element.android.libraries.matrixrtc.impl.bridge.MatrixRtcEventEncryptionInfo
import io.element.android.libraries.matrixrtc.impl.bridge.MatrixRtcToDeviceMessage
import io.element.android.libraries.matrixrtc.impl.bridge.widget.ToDeviceRelay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SessionStateFeederTest {
    @Test
    fun `a spec media key published on the relay reaches the core`() = runTest {
        val manager = RecordingSessionManager()
        val relay = ToDeviceRelay()
        SessionStateFeeder(manager, relay, backgroundScope).start()
        runCurrent()

        relay.publish(aSpecKeyMessage())
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
        val relay = ToDeviceRelay()
        SessionStateFeeder(manager, relay, backgroundScope).start()
        runCurrent()

        relay.publish(aLegacyKeyMessage())
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
        val relay = ToDeviceRelay()
        SessionStateFeeder(manager, relay, backgroundScope).start()
        runCurrent()

        relay.publish(aSpecKeyMessage(encryptionInfo = null))
        relay.publish(aLegacyKeyMessage(encryptionInfo = null))
        runCurrent()

        assertThat(manager.receivedKeys).isEmpty()
        assertThat(manager.receivedLegacyKeys).isEmpty()
    }

    /**
     * What the stopgap costs, pinned so it is not mistaken for a bug: the relay carries what a live bridge
     * hears, and nothing is replayed to a feeder that subscribes later. In production the feeder is the
     * one subscribed for the whole session; the bridges come and go.
     */
    @Test
    fun `a key published before the feeder subscribes is not replayed`() = runTest {
        val manager = RecordingSessionManager()
        val relay = ToDeviceRelay()
        relay.publish(aSpecKeyMessage())

        SessionStateFeeder(manager, relay, backgroundScope).start()
        runCurrent()

        assertThat(manager.receivedKeys).isEmpty()
    }

    private fun aSpecKeyMessage(
        encryptionInfo: MatrixRtcEventEncryptionInfo? = anEncryptionInfo(),
    ) = MatrixRtcToDeviceMessage(
        eventType = MatrixRtcEventTypes.ENCRYPTION_KEY,
        senderId = A_SENDER,
        content = """{"room_id":"${A_ROOM_ID.value}","member_id":"aMember","media_key":{"index":1,"key":"c2VjcmV0"}}""",
        encryptionInfo = encryptionInfo,
    )

    private fun aLegacyKeyMessage(
        encryptionInfo: MatrixRtcEventEncryptionInfo? = anEncryptionInfo(),
    ) = MatrixRtcToDeviceMessage(
        eventType = MatrixRtcEventTypes.ENCRYPTION_KEY_ELEMENT_CALL,
        senderId = A_SENDER,
        content = A_LEGACY_CONTENT,
        encryptionInfo = encryptionInfo,
    )

    private fun anEncryptionInfo() = MatrixRtcEventEncryptionInfo(
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
