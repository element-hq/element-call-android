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
import io.element.android.call.api.rtc.id.UserId
import io.element.android.libraries.matrix.api.exception.ClientException
import io.element.android.libraries.matrix.api.exception.ErrorKind
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.test.A_DEVICE_ID
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.call.api.matrix.FakeMatrixRtcRoomBridge
import io.element.android.call.api.matrix.ElementCallMatrixException
import io.element.android.call.api.matrix.ElementCallDelayedEventAction
import io.element.android.call.api.matrix.ElementCallMatrixRoom
import io.element.android.call.matrix.temporary.widget.MatrixRtcBridgeRegistry
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.lambda.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import uniffi.matrix_rtc_ffi.CommandSenderException
import uniffi.matrix_rtc_ffi.FfiToDeviceRecipient

class MatrixRtcCommandSenderTest {
    @Test
    fun `restartDelayedEvent restarts the delay`() = runTest {
        val updateDelayedEvent = lambdaRecorder { _: String, _: ElementCallDelayedEventAction -> Result.success(Unit) }
        val sender = createSender(bridge = FakeMatrixRtcRoomBridge(updateDelayedEventResult = updateDelayedEvent))

        sender.restartDelayedEvent(A_ROOM_ID.value, A_DELAY_ID)

        updateDelayedEvent.assertions().isCalledOnce()
            .with(value(A_DELAY_ID), value(ElementCallDelayedEventAction.RESTART))
    }

    /**
     * Pinned separately from the restart above because the two callbacks differ only in the action they pass,
     * and swapping them would retire the very membership the dead man's switch exists to protect - dropping us
     * out of a live call minutes later, with nothing in the log tying cause to effect.
     */
    @Test
    fun `cancelDelayedEvent cancels the delay`() = runTest {
        val updateDelayedEvent = lambdaRecorder { _: String, _: ElementCallDelayedEventAction -> Result.success(Unit) }
        val sender = createSender(bridge = FakeMatrixRtcRoomBridge(updateDelayedEventResult = updateDelayedEvent))

        sender.cancelDelayedEvent(A_ROOM_ID.value, A_DELAY_ID)

        updateDelayedEvent.assertions().isCalledOnce()
            .with(value(A_DELAY_ID), value(ElementCallDelayedEventAction.CANCEL))
    }

    @Test
    fun `sendToDeviceMessage addresses every recipient in one send`() = runTest {
        val sendToDevice = lambdaRecorder { _: String, _: Map<UserId, Map<DeviceId, String>> ->
            Result.success(emptyMap<UserId, List<DeviceId>>())
        }
        val sender = createSender(bridge = FakeMatrixRtcRoomBridge(sendToDeviceMessageResult = sendToDevice))

        val deliveries = sender.sendToDeviceMessage(
            recipients = listOf(
                FfiToDeviceRecipient(A_REMOTE_USER_ID.value, A_REMOTE_DEVICE_ID),
                FfiToDeviceRecipient(A_REMOTE_USER_ID.value, ANOTHER_REMOTE_DEVICE_ID),
            ),
            messageType = AN_EVENT_TYPE,
            contentJson = A_CONTENT,
        )

        // One call, both devices of the user in it. The bridge encrypts: RTC media keys must never go out in the clear.
        sendToDevice.assertions().isCalledOnce()
            .with(
                value(AN_EVENT_TYPE),
                value(
                    mapOf(
                        A_REMOTE_USER_ID to mapOf(
                            DeviceId(A_REMOTE_DEVICE_ID) to A_CONTENT,
                            DeviceId(ANOTHER_REMOTE_DEVICE_ID) to A_CONTENT,
                        )
                    )
                ),
            )
        assertThat(deliveries.map { it.deviceId }).containsExactly(A_REMOTE_DEVICE_ID, ANOTHER_REMOTE_DEVICE_ID)
        assertThat(deliveries.map { it.error }).containsExactly(null, null)
    }

    /**
     * The core re-sends to a recipient we report as failed and never re-sends to one we report as delivered, so
     * reporting an undeliverable recipient as served is how a member ends up permanently keyless. One unreachable
     * recipient must also not fail the batch: the others were served.
     */
    @Test
    fun `sendToDeviceMessage reports the recipient that could not be served`() = runTest {
        val bridge = FakeMatrixRtcRoomBridge(
            sendToDeviceMessageResult = { _, _ ->
                Result.success(mapOf(A_REMOTE_USER_ID to listOf(DeviceId(ANOTHER_REMOTE_DEVICE_ID))))
            },
        )

        val deliveries = createSender(bridge = bridge).sendToDeviceMessage(
            recipients = listOf(
                FfiToDeviceRecipient(A_REMOTE_USER_ID.value, A_REMOTE_DEVICE_ID),
                FfiToDeviceRecipient(A_REMOTE_USER_ID.value, ANOTHER_REMOTE_DEVICE_ID),
            ),
            messageType = AN_EVENT_TYPE,
            contentJson = A_CONTENT,
        )

        assertThat(deliveries.single { it.deviceId == A_REMOTE_DEVICE_ID }.error).isNull()
        assertThat(deliveries.single { it.deviceId == ANOTHER_REMOTE_DEVICE_ID }.error).isNotNull()
    }

    /**
     * The core has been observed addressing a media key to the very device it is running on. The homeserver drops
     * such a message, so it can only fail - and it must be reported as a failure rather than silently swallowed,
     * or the core records that device as holding a key it never got.
     */
    @Test
    fun `a key the core addresses to our own device is reported as undelivered`() = runTest {
        val bridge = FakeMatrixRtcRoomBridge(
            sendToDeviceMessageResult = { _, _ -> Result.success(mapOf(A_SESSION_ID to listOf(A_DEVICE_ID))) },
        )

        val deliveries = createSender(bridge = bridge).sendToDeviceMessage(
            recipients = listOf(FfiToDeviceRecipient(A_SESSION_ID.value, A_DEVICE_ID.value)),
            messageType = AN_EVENT_TYPE,
            contentJson = A_CONTENT,
        )

        assertThat(deliveries.single().error).isNotNull()
    }

    /**
     * Reserved for the batch not being attempted at all, which is a different thing from a recipient the
     * homeserver would not take.
     */
    @Test
    fun `sendToDeviceMessage fails when the send itself fails`() = runTest {
        val bridge = FakeMatrixRtcRoomBridge(
            sendToDeviceMessageResult = { _, _ -> Result.failure(IllegalStateException("boom")) },
        )

        val thrown = runCatching {
            createSender(bridge = bridge).sendToDeviceMessage(
                recipients = listOf(FfiToDeviceRecipient(A_REMOTE_USER_ID.value, A_REMOTE_DEVICE_ID)),
                messageType = AN_EVENT_TYPE,
                contentJson = A_CONTENT,
            )
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(CommandSenderException.SendException::class.java)
    }

    /**
     * A to-device message is not scoped to a room, so it goes through whichever bridge is live. None live
     * means no call is up, and that has to reach the core as a send failure rather than a crash.
     */
    @Test
    fun `sendToDeviceMessage fails when no bridge is live`() = runTest {
        val sender = createSender(bridges = MatrixRtcBridgeRegistry())

        val thrown = runCatching {
            sender.sendToDeviceMessage(
                recipients = listOf(FfiToDeviceRecipient(A_REMOTE_USER_ID.value, A_REMOTE_DEVICE_ID)),
                messageType = AN_EVENT_TYPE,
                contentJson = A_CONTENT,
            )
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(CommandSenderException.SendException::class.java)
    }

    /**
     * The widget-driver bridge cannot send sticky events at all, and the core should hear exactly that: a
     * `NotSupported` retires the operation, where a send failure would have it retried for the whole call.
     */
    @Test
    fun `a sticky event the bridge cannot send is reported as unsupported`() = runTest {
        val bridge = FakeMatrixRtcRoomBridge(
            sendStickyEventResult = { _, _, _ -> Result.failure(ElementCallMatrixException.NotSupported("sticky")) },
        )

        val thrown = runCatching {
            createSender(bridge = bridge).sendStickyEvent(A_ROOM_ID.value, AN_EVENT_TYPE, A_CONTENT, durationMs = 60_000uL)
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(CommandSenderException.NotSupported::class.java)
    }

    /**
     * The lifetime is the core's to choose: it knows when it will next refresh the membership, so the
     * duration it hands over reaches the bridge untouched.
     */
    @Test
    fun `sendStickyEvent passes the core's duration through and reports the event id`() = runTest {
        val sendStickyEvent = lambdaRecorder { _: String, _: String, _: ULong -> Result.success(AN_EVENT_ID.value) }
        val sender = createSender(bridge = FakeMatrixRtcRoomBridge(sendStickyEventResult = sendStickyEvent))

        val eventId = sender.sendStickyEvent(A_ROOM_ID.value, AN_EVENT_TYPE, A_CONTENT, durationMs = 60_000uL)

        assertThat(eventId).isEqualTo(AN_EVENT_ID.value)
        sendStickyEvent.assertions().isCalledOnce()
            .with(value(AN_EVENT_TYPE), value(A_CONTENT), value(60_000uL))
    }

    /**
     * In STATE_EVENTS compatibility the membership is sent through here, and its event id is what an MSC4075
     * notification relates to. This one stays on the SDK: the released bindings do report the id.
     */
    @Test
    fun `sendStateEvent reports the event id the homeserver assigned`() = runTest {
        val room = FakeJoinedRoom(sendRawStateEventResult = { _, _, _ -> Result.success(AN_EVENT_ID) })

        val eventId = createSender(room = room).sendStateEvent(A_ROOM_ID.value, AN_EVENT_TYPE, A_STATE_KEY, A_CONTENT)

        assertThat(eventId).isEqualTo(AN_EVENT_ID.value)
    }

    /**
     * A homeserver with no MSC4140 at all. Reported as [CommandSenderException.NotSupported] so the core stops
     * arming a dead man's switch for the rest of the session instead of re-probing a homeserver that has already
     * answered; the join carries on either way.
     */
    @Test
    fun `a homeserver that does not implement delayed events is reported as unsupported`() = runTest {
        val bridge = FakeMatrixRtcRoomBridge(
            sendDelayedEventResult = { _, _, _, _ -> Result.failure(bridgeApiError("M_UNRECOGNIZED", 404, "Unrecognized request")) },
        )

        val thrown = runCatching {
            createSender(bridge = bridge).sendDelayedEvent(A_ROOM_ID.value, AN_EVENT_TYPE, A_CONTENT, delayMs = 8_000uL)
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(CommandSenderException.NotSupported::class.java)
    }

    /**
     * matrix.org implements MSC4140 and refuses to let anyone use it, which is a different errcode reaching the
     * same conclusion.
     */
    @Test
    fun `a homeserver that has disallowed delayed events is reported as unsupported`() = runTest {
        val bridge = FakeMatrixRtcRoomBridge(
            updateDelayedEventResult = { _, _ ->
                Result.failure(bridgeApiError("M_FORBIDDEN", 403, "Sending delayed events has been disallowed"))
            },
        )

        val thrown = runCatching { createSender(bridge = bridge).restartDelayedEvent(A_ROOM_ID.value, A_DELAY_ID) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(CommandSenderException.NotSupported::class.java)
    }

    /**
     * The same verdict must come out of the SDK's own error type: the classifier reads the Matrix error code,
     * whichever transport carried it, so an SDK-backed bridge needs no change here.
     */
    @Test
    fun `the verdict is read off an SDK error the same way`() = runTest {
        val bridge = FakeMatrixRtcRoomBridge(
            sendDelayedEventResult = { _, _, _, _ ->
                Result.failure(matrixApiException(ErrorKind.Forbidden, "M_FORBIDDEN", "Sending delayed events has been disallowed"))
            },
        )

        val thrown = runCatching {
            createSender(bridge = bridge).sendDelayedEvent(A_ROOM_ID.value, AN_EVENT_TYPE, A_CONTENT, delayMs = 8_000uL)
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(CommandSenderException.NotSupported::class.java)
    }

    /**
     * The same errcode for the opposite reason: a delayed *state* event refused because our power level is too
     * low is our problem, not the homeserver's, and would start working the moment that changed. Reporting it as
     * unsupported would retire the dead man's switch for the session over something transient.
     */
    @Test
    fun `a delayed state event refused on power levels stays a retryable send failure`() = runTest {
        val bridge = FakeMatrixRtcRoomBridge(
            sendDelayedEventResult = { _, _, _, _ ->
                Result.failure(bridgeApiError("M_FORBIDDEN", 403, "You don't have permission to post that to the room"))
            },
        )

        val thrown = runCatching {
            createSender(bridge = bridge).sendDelayedStateEvent(A_ROOM_ID.value, A_LEGACY_MEMBER_EVENT_TYPE, A_STATE_KEY, A_CONTENT, delayMs = 8_000uL)
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(CommandSenderException.SendException::class.java)
    }

    @Test
    fun `a delayed event that fails for any other reason stays a retryable send failure`() = runTest {
        val bridge = FakeMatrixRtcRoomBridge(sendDelayedEventResult = { _, _, _, _ -> Result.failure(IllegalStateException("boom")) })

        val thrown = runCatching {
            createSender(bridge = bridge).sendDelayedEvent(A_ROOM_ID.value, AN_EVENT_TYPE, A_CONTENT, delayMs = 8_000uL)
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(CommandSenderException.SendException::class.java)
    }

    /**
     * A bridge that is not running, or that heard nothing back, has said nothing about the homeserver: the next
     * attempt may well have a bridge, so the dead man's switch is not retired over it.
     */
    @Test
    fun `a bridge that is not running or timed out stays a retryable send failure`() = runTest {
        listOf(
            ElementCallMatrixException.NotRunning(A_ROOM_ID),
            ElementCallMatrixException.Timeout("send_event"),
        ).forEach { failure ->
            val bridge = FakeMatrixRtcRoomBridge(sendDelayedEventResult = { _, _, _, _ -> Result.failure(failure) })

            val thrown = runCatching {
                createSender(bridge = bridge).sendDelayedEvent(A_ROOM_ID.value, AN_EVENT_TYPE, A_CONTENT, delayMs = 8_000uL)
            }.exceptionOrNull()

            assertThat(thrown).isInstanceOf(CommandSenderException.SendException::class.java)
        }
    }

    /**
     * The dead man's switch for a membership carried as room state. Pinned because the message-like
     * `sendDelayedEvent` has no state key and so cannot stand in for it: routed there, the delayed leave
     * would be published under no membership at all and would never retire ours.
     */
    @Test
    fun `sendDelayedStateEvent schedules the leave under its state key`() = runTest {
        val sendDelayedEvent = lambdaRecorder { _: String, _: String?, _: String, _: ULong -> Result.success(A_DELAY_ID) }
        val sender = createSender(bridge = FakeMatrixRtcRoomBridge(sendDelayedEventResult = sendDelayedEvent))

        val delayId = sender.sendDelayedStateEvent(
            roomId = A_ROOM_ID.value,
            eventType = A_LEGACY_MEMBER_EVENT_TYPE,
            stateKey = A_STATE_KEY,
            contentJson = A_CONTENT,
            delayMs = 8_000uL,
        )

        assertThat(delayId).isEqualTo(A_DELAY_ID)
        sendDelayedEvent.assertions().isCalledOnce()
            .with(value(A_LEGACY_MEMBER_EVENT_TYPE), value(A_STATE_KEY), value(A_CONTENT), value(8_000uL))
    }

    @Test
    fun `sendDelayedEvent sends a message-like event, with no state key`() = runTest {
        val sendDelayedEvent = lambdaRecorder { _: String, _: String?, _: String, _: ULong -> Result.success(A_DELAY_ID) }
        val sender = createSender(bridge = FakeMatrixRtcRoomBridge(sendDelayedEventResult = sendDelayedEvent))

        val delayId = sender.sendDelayedEvent(A_ROOM_ID.value, AN_EVENT_TYPE, A_CONTENT, delayMs = 8_000uL)

        assertThat(delayId).isEqualTo(A_DELAY_ID)
        sendDelayedEvent.assertions().isCalledOnce()
            .with(value(AN_EVENT_TYPE), value(null), value(A_CONTENT), value(8_000uL))
    }

    @Test
    fun `a command for a room with no live bridge fails rather than crossing the FFI`() = runTest {
        val sender = createSender(bridges = MatrixRtcBridgeRegistry())

        val thrown = runCatching { sender.cancelDelayedEvent(A_ROOM_ID.value, A_DELAY_ID) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(CommandSenderException.SendException::class.java)
    }

    @Test
    fun `a command for a room we are not joined to fails rather than crossing the FFI`() = runTest {
        val sender = MatrixRtcCommandSender(
            client = FakeMatrixClient(),
            commandDispatcher = Dispatchers.Unconfined,
            roomProvider = { null },
            bridges = MatrixRtcBridgeRegistry(),
        )

        val thrown = runCatching { sender.sendStateEvent(A_ROOM_ID.value, AN_EVENT_TYPE, A_STATE_KEY, A_CONTENT) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(CommandSenderException::class.java)
    }

    private fun bridgeApiError(errcode: String, httpStatus: Int, message: String) = ElementCallMatrixException.MatrixApi(
        errcode = errcode,
        httpStatus = httpStatus,
        message = message,
    )

    private fun matrixApiException(kind: ErrorKind, code: String, message: String) = ClientException.MatrixApi(
        kind = kind,
        code = code,
        message = message,
        details = null,
    )

    private fun createSender(
        client: FakeMatrixClient = FakeMatrixClient(),
        room: JoinedRoom = FakeJoinedRoom(),
        bridge: ElementCallMatrixRoom = FakeMatrixRtcRoomBridge(),
        bridges: MatrixRtcBridgeRegistry = MatrixRtcBridgeRegistry().apply { register(bridge) },
    ) = MatrixRtcCommandSender(
        client = client,
        commandDispatcher = Dispatchers.Unconfined,
        roomProvider = { room },
        bridges = bridges,
    )

    private companion object {
        const val A_DELAY_ID = "aDelayId"
        val AN_EVENT_ID = EventId("\$anEventId")
        const val AN_EVENT_TYPE = "org.matrix.msc4143.rtc.member"
        const val A_CONTENT = """{"application":"m.call"}"""

        // Only ever sent in STATE_EVENTS compatibility, where the membership is room state keyed by
        // user, device and application.
        const val A_LEGACY_MEMBER_EVENT_TYPE = "org.matrix.msc3401.call.member"
        const val A_STATE_KEY = "_@alice:example.org_ADEVICEID_m.call"

        // Distinct from the A_DEVICE_ID / A_SESSION_ID that FakeMatrixClient uses for our own identity,
        // so that "a remote recipient" and "ourselves" are never accidentally the same device.
        const val A_REMOTE_DEVICE_ID = "AREMOTEDEVICEID"
        const val ANOTHER_REMOTE_DEVICE_ID = "ANOTHERREMOTEDEVICEID"
        val A_REMOTE_USER_ID = UserId("@bob:example.org")
    }
}
