/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import io.element.android.call.api.matrix.ElementCallDelayedEventAction
import io.element.android.call.api.matrix.ElementCallEventEncryptionInfo
import io.element.android.call.api.matrix.ElementCallMatrixException
import io.element.android.call.api.matrix.ElementCallMatrixRoom
import io.element.android.call.api.matrix.ElementCallMatrixTransport
import io.element.android.call.api.matrix.ElementCallRoomEvent
import io.element.android.call.api.rtc.id.DeviceId
import io.element.android.call.api.rtc.id.EventId
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.api.rtc.id.UserId
import io.element.android.call.impl.util.runCatchingExceptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.rtc.BackendSubscription
import org.matrix.rtc.FfiBackendException
import org.matrix.rtc.FfiEventEncryption
import org.matrix.rtc.FfiEventIn
import org.matrix.rtc.FfiOpenIdToken
import org.matrix.rtc.FfiRoomSubjects
import org.matrix.rtc.FfiToDeviceDelivery
import org.matrix.rtc.FfiToDeviceMessageIn
import org.matrix.rtc.FfiToDeviceRecipient
import org.matrix.rtc.MatrixBackend
import org.matrix.rtc.RoomSink
import org.matrix.rtc.ToDeviceSink
import timber.log.Timber

/**
 * The core's Matrix client: sends go to the [ElementCallMatrixTransport] or to the room [roomProvider]
 * has open, and each subscription collects the matching flows into the core's sink.
 *
 * The core classifies failures itself, so a failure only carries the Matrix error code and status.
 */
internal class DefaultMatrixBackend(
    private val transport: ElementCallMatrixTransport,
    private val transportDiscovery: RtcTransportDiscovery,
    private val roomProvider: (RoomId) -> ElementCallMatrixRoom?,
    /** Lives as long as the session: subscriptions run here until the core cancels them. */
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) : MatrixBackend {
    override fun ownUserId(): String = transport.userId.value

    override fun ownDeviceId(): String = transport.deviceId.value

    override suspend fun sendStickyEvent(roomId: String, eventType: String, contentJson: String, durationMs: ULong): String =
        send("sendStickyEvent($eventType)") { room(roomId).sendStickyEvent(eventType, contentJson, durationMs).getOrThrow() }

    override suspend fun sendDelayedEvent(roomId: String, eventType: String, stateKey: String?, contentJson: String, delayMs: ULong): String =
        send("sendDelayedEvent($eventType)") { room(roomId).sendDelayedEvent(eventType, stateKey, contentJson, delayMs).getOrThrow() }

    // Restart and cancel differ only by the action, and swapping them retires a membership we still hold.
    override suspend fun restartDelayedEvent(roomId: String, delayId: String) =
        send("restartDelayedEvent") { room(roomId).updateDelayedEvent(delayId, ElementCallDelayedEventAction.RESTART).getOrThrow() }

    override suspend fun cancelDelayedEvent(roomId: String, delayId: String) =
        send("cancelDelayedEvent") { room(roomId).updateDelayedEvent(delayId, ElementCallDelayedEventAction.CANCEL).getOrThrow() }

    override suspend fun sendStateEvent(roomId: String, eventType: String, stateKey: String, contentJson: String): String =
        send("sendStateEvent($eventType)") { room(roomId).sendStateEvent(eventType, stateKey, contentJson).getOrThrow().value }

    override suspend fun sendRoomEvent(roomId: String, eventType: String, contentJson: String): String =
        send("sendRoomEvent($eventType)") { room(roomId).sendRoomEvent(eventType, contentJson).getOrThrow().value }

    override suspend fun redactEvent(roomId: String, eventId: String, reason: String?) =
        send("redactEvent") { room(roomId).redactEvent(EventId(eventId), reason).getOrThrow() }

    override suspend fun sendToDeviceMessage(
        recipients: List<FfiToDeviceRecipient>,
        messageType: String,
        contentJson: String,
    ): List<FfiToDeviceDelivery> = send("sendToDeviceMessage($messageType to [${recipients.joinToString { "${it.userId}/${it.deviceId}" }}])") {
        val failures = transport.sendToDeviceMessage(
            eventType = messageType,
            messages = recipients
                .groupBy { UserId(it.userId) }
                .mapValues { (_, userRecipients) -> userRecipients.associate { DeviceId(it.deviceId) to contentJson } },
        ).getOrThrow()
        if (failures.isNotEmpty()) Timber.w("MatrixRTC: to-device $messageType not delivered to $failures")
        // One verdict per recipient: the core re-sends only to those reported failed.
        recipients.map { recipient ->
            val failed = failures[UserId(recipient.userId)]?.any { it.value == recipient.deviceId } == true
            FfiToDeviceDelivery(
                userId = recipient.userId,
                deviceId = recipient.deviceId,
                error = if (failed) "Homeserver did not accept the message for this device" else null,
            )
        }
    }

    override fun subscribeRoom(roomId: String, subjects: FfiRoomSubjects, sink: RoomSink): BackendSubscription =
        subscribeRoom(roomId, subjects, sink.asRoomSubjectSink())

    override fun subscribeToDevice(eventTypes: List<String>, sink: ToDeviceSink): BackendSubscription =
        subscribeToDevice(eventTypes, sink::onToDeviceMessage)

    fun subscribeRoom(roomId: String, subjects: FfiRoomSubjects, sink: RoomSubjectSink): BackendSubscription {
        val room = room(roomId)
        Timber.i("MatrixRTC: subscribing $roomId to state ${subjects.stateEventTypes}, timeline ${subjects.timelineEventTypes}")
        val job = scope.launch {
            deliver("sticky events", room.stickyEvents()) { sink.onStickyEvents(it.map { event -> event.toFfi() }) }
            for (type in subjects.stateEventTypes) {
                // The port shares one bucket between alias spellings; the core wants each under its own.
                deliver("state $type", room.stateEvents(type)) { events -> sink.onStateEvents(type, events.filter { it.eventType == type }.map { it.toFfi() }) }
            }
            deliver("joined members", room.joinedMemberIds) { members -> sink.onJoinedMembers(members.map { it.value }) }
            deliver("encryption", room.isEncrypted) { sink.onEncryption(it) }
            if (subjects.timelineEventTypes.isNotEmpty()) {
                deliver("timeline", room.timelineEvents(subjects.timelineEventTypes)) { events -> sink.onTimelineEvents(events.map { it.toFfi() }) }
            }
            deliver("redactions", room.redactions()) { sink.onRedaction(it.value) }
        }
        return JobSubscription(job)
    }

    fun subscribeToDevice(eventTypes: List<String>, onMessage: (FfiToDeviceMessageIn) -> Unit): BackendSubscription {
        val messages = transport.toDeviceMessages(eventTypes.toSet()).map { message ->
            FfiToDeviceMessageIn(
                sender = message.senderId.value,
                eventType = message.eventType,
                contentJson = message.content,
                encryption = message.encryptionInfo.toFfi(),
            )
        }
        return JobSubscription(scope.deliver("to-device $eventTypes", messages, onMessage))
    }

    override suspend fun relations(roomId: String, eventId: String, relType: String, eventType: String): List<FfiEventIn> =
        send("relations($relType, $eventType)") { room(roomId).relations(EventId(eventId), relType, eventType).getOrThrow().map { it.toFfi() } }

    override suspend fun openidToken(): FfiOpenIdToken = send("openidToken") {
        val token = transport.getOpenIdToken().getOrThrow()
        FfiOpenIdToken(
            accessToken = token.accessToken,
            tokenType = token.tokenType,
            matrixServerName = token.matrixServerName,
            expiresInSecs = token.expiresInSeconds.toULong(),
        )
    }

    override suspend fun rtcTransports(): String = send("rtcTransports") { transportDiscovery.discoverJson().getOrThrow() }

    private fun room(roomId: String): ElementCallMatrixRoom {
        return roomProvider(RoomId(roomId)) ?: throw FfiBackendException.Failed(errcode = null, status = null, reason = "No open room for $roomId")
    }

    /** Off uniffi's thread, logged on entry, and failing as the core reads failures. */
    private suspend fun <T> send(description: String, block: suspend () -> T): T {
        Timber.i("MatrixRTC backend: $description")
        return try {
            withContext(ioDispatcher) { block() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Timber.w(throwable, "MatrixRTC backend: $description failed")
            throw throwable.toFfi()
        }
    }

    /** Each subject on its own, and a failing one only ends itself: it never reaches the session scope. */
    private fun <T> CoroutineScope.deliver(what: String, flow: Flow<T>, block: (T) -> Unit): Job =
        launch(ioDispatcher) {
            runCatchingExceptions { flow.collect(block) }
                .onFailure { Timber.w(it, "MatrixRTC backend: the $what feed ended") }
        }

    private class JobSubscription(private val job: Job) : BackendSubscription {
        override fun cancel() = job.cancel()
    }
}

/** The core's [RoomSink], which is native: an interface a JVM test can implement. */
internal interface RoomSubjectSink {
    fun onStickyEvents(events: List<FfiEventIn>)

    fun onStateEvents(eventType: String, events: List<FfiEventIn>)

    fun onJoinedMembers(userIds: List<String>)

    fun onEncryption(encrypted: Boolean)

    fun onTimelineEvents(events: List<FfiEventIn>)

    fun onRedaction(eventId: String)
}

private fun RoomSink.asRoomSubjectSink() = object : RoomSubjectSink {
    override fun onStickyEvents(events: List<FfiEventIn>) = this@asRoomSubjectSink.onStickyEvents(events)

    override fun onStateEvents(eventType: String, events: List<FfiEventIn>) = this@asRoomSubjectSink.onStateEvents(eventType, events)

    override fun onJoinedMembers(userIds: List<String>) = this@asRoomSubjectSink.onJoinedMembers(userIds)

    override fun onEncryption(encrypted: Boolean) = this@asRoomSubjectSink.onEncryption(encrypted)

    override fun onTimelineEvents(events: List<FfiEventIn>) = this@asRoomSubjectSink.onTimelineEvents(events)

    override fun onRedaction(eventId: String) = this@asRoomSubjectSink.onRedaction(eventId)
}

private fun Throwable.toFfi(): FfiBackendException = when (this) {
    is FfiBackendException -> this
    is ElementCallMatrixException.MatrixApi -> FfiBackendException.Failed(errcode = errcode, status = httpStatus?.toUShort(), reason = message.orEmpty())
    else -> FfiBackendException.Failed(errcode = null, status = null, reason = message ?: javaClass.simpleName)
}

private fun ElementCallRoomEvent.toFfi() = FfiEventIn(
    eventId = eventId.value,
    sender = sender.value,
    eventType = eventType,
    stateKey = stateKey,
    originServerTs = timestampMs.toULong(),
    contentJson = contentJson,
    encryption = encryptionInfo.toFfi(),
)

private fun ElementCallEventEncryptionInfo?.toFfi() = FfiEventEncryption(
    encrypted = this != null,
    senderDeviceId = this?.senderDeviceId?.value,
    senderCrossSigned = this?.isSenderCrossSigned,
)
