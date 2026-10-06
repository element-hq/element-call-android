/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.matrix.ElementCallDelayedEventAction
import io.element.android.call.api.matrix.ElementCallEventEncryptionInfo
import io.element.android.call.api.matrix.ElementCallMatrixException
import io.element.android.call.api.matrix.ElementCallMatrixRoom
import io.element.android.call.api.matrix.ElementCallMatrixTransport
import io.element.android.call.api.matrix.ElementCallRoomEvent
import io.element.android.call.api.matrix.ElementCallToDeviceMessage
import io.element.android.call.api.rtc.id.DeviceId
import io.element.android.call.api.rtc.id.EventId
import io.element.android.call.api.rtc.id.UserId
import io.element.android.call.impl.util.runCatchingExceptions
import io.element.android.call.test.A_DEVICE_ID
import io.element.android.call.test.A_ROOM_ID
import io.element.android.call.test.A_USER_ID
import io.element.android.call.test.FakeElementCallMatrixRoom
import io.element.android.call.test.FakeElementCallMatrixTransport
import io.element.android.call.tests.testutils.lambda.lambdaRecorder
import io.element.android.call.tests.testutils.lambda.value
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.matrix.rtc.FfiBackendException
import org.matrix.rtc.FfiEventEncryption
import org.matrix.rtc.FfiEventIn
import org.matrix.rtc.FfiRoomSubjects
import org.matrix.rtc.FfiToDeviceMessageIn
import org.matrix.rtc.FfiToDeviceRecipient

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultMatrixBackendTest {
    @Test
    fun `restartDelayedEvent restarts the delay`() = runTest {
        val updateDelayedEvent = lambdaRecorder { _: String, _: ElementCallDelayedEventAction -> Result.success(Unit) }
        val backend = createBackend(room = FakeElementCallMatrixRoom(updateDelayedEventResult = updateDelayedEvent))

        backend.restartDelayedEvent(A_ROOM_ID.value, A_DELAY_ID)

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
        val backend = createBackend(room = FakeElementCallMatrixRoom(updateDelayedEventResult = updateDelayedEvent))

        backend.cancelDelayedEvent(A_ROOM_ID.value, A_DELAY_ID)

        updateDelayedEvent.assertions().isCalledOnce()
            .with(value(A_DELAY_ID), value(ElementCallDelayedEventAction.CANCEL))
    }

    @Test
    fun `sendToDeviceMessage addresses every recipient in one send`() = runTest {
        val sendToDevice = lambdaRecorder { _: String, _: Map<UserId, Map<DeviceId, String>> ->
            Result.success(emptyMap<UserId, List<DeviceId>>())
        }
        val backend = createBackend(transport = FakeElementCallMatrixTransport(sendToDeviceMessageResult = sendToDevice))

        val deliveries = backend.sendToDeviceMessage(
            recipients = listOf(
                FfiToDeviceRecipient(A_REMOTE_USER_ID.value, A_REMOTE_DEVICE_ID),
                FfiToDeviceRecipient(A_REMOTE_USER_ID.value, ANOTHER_REMOTE_DEVICE_ID),
            ),
            messageType = AN_EVENT_TYPE,
            contentJson = A_CONTENT,
        )

        // One call, both devices of the user in it. The transport encrypts: RTC media keys must never go out in the clear.
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
        val transport = FakeElementCallMatrixTransport(
            sendToDeviceMessageResult = { _, _ ->
                Result.success(mapOf(A_REMOTE_USER_ID to listOf(DeviceId(ANOTHER_REMOTE_DEVICE_ID))))
            },
        )

        val deliveries = createBackend(transport = transport).sendToDeviceMessage(
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
        val transport = FakeElementCallMatrixTransport(
            sendToDeviceMessageResult = { _, _ -> Result.success(mapOf(A_USER_ID to listOf(A_DEVICE_ID))) },
        )

        val deliveries = createBackend(transport = transport).sendToDeviceMessage(
            recipients = listOf(FfiToDeviceRecipient(A_USER_ID.value, A_DEVICE_ID.value)),
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
        val transport = FakeElementCallMatrixTransport(
            sendToDeviceMessageResult = { _, _ -> Result.failure(IllegalStateException("boom")) },
        )

        val thrown = runCatchingExceptions {
            createBackend(transport = transport).sendToDeviceMessage(
                recipients = listOf(FfiToDeviceRecipient(A_REMOTE_USER_ID.value, A_REMOTE_DEVICE_ID)),
                messageType = AN_EVENT_TYPE,
                contentJson = A_CONTENT,
            )
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(FfiBackendException.Failed::class.java)
    }

    /**
     * The lifetime is the core's to choose: it knows when it will next refresh the membership, so the
     * duration it hands over reaches the room untouched.
     */
    @Test
    fun `sendStickyEvent passes the core's duration through and reports the event id`() = runTest {
        val sendStickyEvent = lambdaRecorder { _: String, _: String, _: ULong -> Result.success(AN_EVENT_ID.value) }
        val backend = createBackend(room = FakeElementCallMatrixRoom(sendStickyEventResult = sendStickyEvent))

        val eventId = backend.sendStickyEvent(A_ROOM_ID.value, AN_EVENT_TYPE, A_CONTENT, durationMs = 60_000uL)

        assertThat(eventId).isEqualTo(AN_EVENT_ID.value)
        sendStickyEvent.assertions().isCalledOnce()
            .with(value(AN_EVENT_TYPE), value(A_CONTENT), value(60_000uL))
    }

    /**
     * In STATE_EVENTS compatibility the membership is sent through here, and its event id is what an MSC4075
     * notification relates to.
     */
    @Test
    fun `sendStateEvent reports the event id the homeserver assigned`() = runTest {
        val room = FakeElementCallMatrixRoom(sendStateEventResult = { _, _, _ -> Result.success(AN_EVENT_ID) })

        val eventId = createBackend(room = room).sendStateEvent(A_ROOM_ID.value, AN_EVENT_TYPE, A_STATE_KEY, A_CONTENT)

        assertThat(eventId).isEqualTo(AN_EVENT_ID.value)
    }

    /**
     * The dead man's switch for a membership carried as room state: without its state key the delayed
     * leave would be published under no membership at all and would never retire ours.
     */
    @Test
    fun `a delayed state event is scheduled under its state key`() = runTest {
        val sendDelayedEvent = lambdaRecorder { _: String, _: String?, _: String, _: ULong -> Result.success(A_DELAY_ID) }
        val backend = createBackend(room = FakeElementCallMatrixRoom(sendDelayedEventResult = sendDelayedEvent))

        val delayId = backend.sendDelayedEvent(
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
    fun `a delayed event with no state key is message-like`() = runTest {
        val sendDelayedEvent = lambdaRecorder { _: String, _: String?, _: String, _: ULong -> Result.success(A_DELAY_ID) }
        val backend = createBackend(room = FakeElementCallMatrixRoom(sendDelayedEventResult = sendDelayedEvent))

        val delayId = backend.sendDelayedEvent(A_ROOM_ID.value, AN_EVENT_TYPE, stateKey = null, A_CONTENT, delayMs = 8_000uL)

        assertThat(delayId).isEqualTo(A_DELAY_ID)
        sendDelayedEvent.assertions().isCalledOnce()
            .with(value(AN_EVENT_TYPE), value(null), value(A_CONTENT), value(8_000uL))
    }

    @Test
    fun `a send for a room that is not open fails as the core reads failures`() = runTest {
        val backend = createBackend(room = null)

        listOf<suspend () -> Any>(
            { backend.cancelDelayedEvent(A_ROOM_ID.value, A_DELAY_ID) },
            { backend.sendStateEvent(A_ROOM_ID.value, AN_EVENT_TYPE, A_STATE_KEY, A_CONTENT) },
            { backend.sendRoomEvent(A_ROOM_ID.value, A_NOTIFICATION_EVENT_TYPE, A_CONTENT) },
            { backend.redactEvent(A_ROOM_ID.value, AN_EVENT_ID.value, reason = null) },
        ).forEach { command ->
            val thrown = runCatchingExceptions { command() }.exceptionOrNull()
            assertThat(thrown).isInstanceOf(FfiBackendException.Failed::class.java)
        }
    }

    /**
     * In the state-event compat mode the MSC4075 notification is an ordinary room event, so this is the send
     * that makes a call ring.
     */
    @Test
    fun `sendRoomEvent sends the event to the room and returns its id`() = runTest {
        val sendRoomEvent = lambdaRecorder { _: String, _: String -> Result.success(AN_EVENT_ID) }
        val backend = createBackend(room = FakeElementCallMatrixRoom(sendRoomEventResult = sendRoomEvent))

        val eventId = backend.sendRoomEvent(A_ROOM_ID.value, A_NOTIFICATION_EVENT_TYPE, A_CONTENT)

        assertThat(eventId).isEqualTo(AN_EVENT_ID.value)
        sendRoomEvent.assertions().isCalledOnce().with(value(A_NOTIFICATION_EVENT_TYPE), value(A_CONTENT))
    }

    @Test
    fun `redactEvent redacts the event in the room`() = runTest {
        val redactEvent = lambdaRecorder { _: EventId, _: String? -> Result.success(Unit) }
        val backend = createBackend(room = FakeElementCallMatrixRoom(redactEventResult = redactEvent))

        backend.redactEvent(A_ROOM_ID.value, AN_EVENT_ID.value, reason = "hand lowered")

        redactEvent.assertions().isCalledOnce().with(value(AN_EVENT_ID), value("hand lowered"))
    }

    /** The core classifies a failure (a delayed-event refusal, for one) from the errcode and status alone. */
    @Test
    fun `a Matrix error reaches the core with its errcode and status`() = runTest {
        val room = FakeElementCallMatrixRoom(
            sendDelayedEventResult = { _, _, _, _ -> Result.failure(matrixApiError("M_UNRECOGNIZED", 404, "Unrecognized request")) },
        )

        val thrown = runCatchingExceptions {
            createBackend(room = room).sendDelayedEvent(A_ROOM_ID.value, AN_EVENT_TYPE, stateKey = null, A_CONTENT, delayMs = 8_000uL)
        }.exceptionOrNull() as FfiBackendException.Failed

        assertThat(thrown.errcode).isEqualTo("M_UNRECOGNIZED")
        assertThat(thrown.status).isEqualTo(404.toUShort())
    }

    /** The core opens a room only once each subject has delivered, so an empty set must still be delivered. */
    @Test
    fun `a room subscription delivers every subject's current set, an empty one included`() = runTest {
        val room = FakeElementCallMatrixRoom(
            stickyEvents = MutableStateFlow(emptyList()),
            // Both spellings share one bucket in the port; each reaches the core under its own type.
            stateEventsResult = { MutableStateFlow(listOf(aStateEvent(A_LEGACY_MEMBER_EVENT_TYPE), aStateEvent(A_STABLE_MEMBER_EVENT_TYPE))) },
        )
        val sink = RecordingRoomSink()

        createBackend(
            room = room
        ).subscribeRoom(A_ROOM_ID.value, FfiRoomSubjects(listOf(A_LEGACY_MEMBER_EVENT_TYPE, A_STABLE_MEMBER_EVENT_TYPE), emptyList()), sink)
        runCurrent()

        assertThat(sink.sticky).containsExactly(emptyList<FfiEventIn>())
        assertThat(sink.state.map { (type, events) -> type to events.map { it.eventType } }).containsExactly(
            A_LEGACY_MEMBER_EVENT_TYPE to listOf(A_LEGACY_MEMBER_EVENT_TYPE),
            A_STABLE_MEMBER_EVENT_TYPE to listOf(A_STABLE_MEMBER_EVENT_TYPE),
        )
        assertThat(sink.members).containsExactly(listOf(A_USER_ID.value))
        assertThat(sink.encryption).containsExactly(true)
    }

    @Test
    fun `cancelling a room subscription stops its feeds, and cancelling twice is harmless`() = runTest {
        val sticky = MutableStateFlow(emptyList<ElementCallRoomEvent>())
        val sink = RecordingRoomSink()
        val subscription = createBackend(room = FakeElementCallMatrixRoom(stickyEvents = sticky))
            .subscribeRoom(A_ROOM_ID.value, FfiRoomSubjects(emptyList(), emptyList()), sink)
        runCurrent()

        subscription.cancel()
        subscription.cancel()
        sticky.value = listOf(aStateEvent(AN_EVENT_TYPE))
        runCurrent()

        assertThat(sink.sticky).hasSize(1)
    }

    /** Only what the client said: a cross-signing status it could not give stays null. */
    @Test
    fun `a to-device message reaches the core with what the client said about its encryption`() = runTest {
        val transport = FakeElementCallMatrixTransport()
        val received = mutableListOf<FfiToDeviceMessageIn>()
        createBackend(transport = transport).subscribeToDevice(listOf(AN_EVENT_TYPE)) { received += it }
        runCurrent()

        transport.givenToDeviceMessage(
            ElementCallToDeviceMessage(
                eventType = AN_EVENT_TYPE,
                senderId = A_REMOTE_USER_ID,
                content = A_CONTENT,
                encryptionInfo = ElementCallEventEncryptionInfo(A_REMOTE_USER_ID, DeviceId(A_REMOTE_DEVICE_ID), null, isSenderCrossSigned = null),
            )
        )
        runCurrent()

        assertThat(received.single().encryption).isEqualTo(FfiEventEncryption(encrypted = true, senderDeviceId = A_REMOTE_DEVICE_ID, senderCrossSigned = null))
    }

    @Test
    fun `the transports are answered verbatim, as the JSON array the core parses`() = runTest {
        val transport = FakeElementCallMatrixTransport(
            getUrlResult = { Result.success("""{"rtc_transports":[{"type":"livekit","livekit_service_url":"https://sfu.example.org"}]}""") },
        )

        val transports = createBackend(transport = transport).rtcTransports()

        assertThat(transports).isEqualTo("""[{"type":"livekit","livekit_service_url":"https://sfu.example.org"}]""")
    }

    private fun aStateEvent(type: String) = ElementCallRoomEvent(
        eventId = AN_EVENT_ID,
        sender = A_REMOTE_USER_ID,
        eventType = type,
        stateKey = A_STATE_KEY,
        timestampMs = 0L,
        contentJson = A_CONTENT,
        encryptionInfo = null,
    )

    private class RecordingRoomSink : RoomSubjectSink {
        val sticky = mutableListOf<List<FfiEventIn>>()
        val state = mutableListOf<Pair<String, List<FfiEventIn>>>()
        val members = mutableListOf<List<String>>()
        val encryption = mutableListOf<Boolean>()

        override fun onStickyEvents(events: List<FfiEventIn>) {
            sticky += events
        }

        override fun onStateEvents(eventType: String, events: List<FfiEventIn>) {
            state += eventType to events
        }

        override fun onJoinedMembers(userIds: List<String>) {
            members += userIds
        }

        override fun onEncryption(encrypted: Boolean) {
            encryption += encrypted
        }

        override fun onTimelineEvents(events: List<FfiEventIn>) = Unit

        override fun onRedaction(eventId: String) = Unit
    }

    private fun matrixApiError(errcode: String, httpStatus: Int, message: String) = ElementCallMatrixException.MatrixApi(
        errcode = errcode,
        httpStatus = httpStatus,
        message = message,
    )

    private fun TestScope.createBackend(
        transport: ElementCallMatrixTransport = FakeElementCallMatrixTransport(),
        room: ElementCallMatrixRoom? = FakeElementCallMatrixRoom(),
    ) = DefaultMatrixBackend(
        transport = transport,
        transportDiscovery = RtcTransportDiscovery(transport),
        roomProvider = { room },
        scope = backgroundScope,
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    private companion object {
        const val A_DELAY_ID = "aDelayId"
        val AN_EVENT_ID = EventId("\$anEventId")
        const val AN_EVENT_TYPE = "org.matrix.msc4143.rtc.member"
        const val A_NOTIFICATION_EVENT_TYPE = "org.matrix.msc4075.rtc.notification"
        const val A_CONTENT = """{"application":"m.call"}"""

        // Only ever sent in STATE_EVENTS compatibility, where the membership is room state keyed by
        // user, device and application.
        const val A_LEGACY_MEMBER_EVENT_TYPE = "org.matrix.msc3401.call.member"
        const val A_STABLE_MEMBER_EVENT_TYPE = "m.call.member"
        const val A_STATE_KEY = "_@alice:example.org_ADEVICEID_m.call"

        // Distinct from the A_DEVICE_ID / A_USER_ID the fake transport uses for our own identity,
        // so that "a remote recipient" and "ourselves" are never accidentally the same device.
        const val A_REMOTE_DEVICE_ID = "AREMOTEDEVICEID"
        const val ANOTHER_REMOTE_DEVICE_ID = "ANOTHERREMOTEDEVICEID"
        val A_REMOTE_USER_ID = UserId("@bob:example.org")
    }
}
