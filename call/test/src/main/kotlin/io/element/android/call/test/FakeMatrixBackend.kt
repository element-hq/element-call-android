/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.test

import org.matrix.rtc.BackendSubscription
import org.matrix.rtc.FfiEventIn
import org.matrix.rtc.FfiOpenIdToken
import org.matrix.rtc.FfiRoomSubjects
import org.matrix.rtc.FfiToDeviceDelivery
import org.matrix.rtc.FfiToDeviceRecipient
import org.matrix.rtc.MatrixBackend
import org.matrix.rtc.RoomSink
import org.matrix.rtc.ToDeviceSink

/**
 * The RTC core's Matrix client, for a stack built over a fake RTC client, which never reaches it. A test
 * that drives the real core provides the lambdas it needs; subscriptions are recorded and deliver nothing.
 */
class FakeMatrixBackend(
    private val userId: String = A_USER_ID.value,
    private val deviceId: String = A_DEVICE_ID.value,
    private val sendStateEventResult: (roomId: String, eventType: String, stateKey: String, contentJson: String) -> String =
        { _, _, _, _ -> lambdaError() },
    private val sendRoomEventResult: (roomId: String, eventType: String, contentJson: String) -> String = { _, _, _ -> lambdaError() },
    private val sendStickyEventResult: (roomId: String, eventType: String, contentJson: String, durationMs: ULong) -> String =
        { _, _, _, _ -> lambdaError() },
    private val sendDelayedEventResult: (roomId: String, eventType: String, stateKey: String?, contentJson: String, delayMs: ULong) -> String =
        { _, _, _, _, _ -> lambdaError() },
    private val updateDelayedEventResult: (roomId: String, delayId: String, restart: Boolean) -> Unit = { _, _, _ -> lambdaError() },
    private val redactEventResult: (roomId: String, eventId: String, reason: String?) -> Unit = { _, _, _ -> lambdaError() },
    private val sendToDeviceMessageResult: (recipients: List<FfiToDeviceRecipient>, messageType: String, contentJson: String) -> List<FfiToDeviceDelivery> =
        { _, _, _ -> lambdaError() },
    private val openidTokenResult: () -> FfiOpenIdToken = { lambdaError() },
    private val rtcTransportsResult: () -> String = { "[]" },
) : MatrixBackend {
    /** The subjects of every room subscription, by room id, in order. */
    val roomSubscriptions = mutableListOf<Pair<String, FfiRoomSubjects>>()

    /** The event types of every to-device subscription, in order. */
    val toDeviceSubscriptions = mutableListOf<List<String>>()

    override fun ownUserId(): String = userId

    override fun ownDeviceId(): String = deviceId

    override suspend fun sendStickyEvent(roomId: String, eventType: String, contentJson: String, durationMs: ULong): String =
        sendStickyEventResult(roomId, eventType, contentJson, durationMs)

    override suspend fun sendDelayedEvent(roomId: String, eventType: String, stateKey: String?, contentJson: String, delayMs: ULong): String =
        sendDelayedEventResult(roomId, eventType, stateKey, contentJson, delayMs)

    override suspend fun restartDelayedEvent(roomId: String, delayId: String) = updateDelayedEventResult(roomId, delayId, true)

    override suspend fun cancelDelayedEvent(roomId: String, delayId: String) = updateDelayedEventResult(roomId, delayId, false)

    override suspend fun sendToDeviceMessage(recipients: List<FfiToDeviceRecipient>, messageType: String, contentJson: String): List<FfiToDeviceDelivery> =
        sendToDeviceMessageResult(recipients, messageType, contentJson)

    override suspend fun sendStateEvent(roomId: String, eventType: String, stateKey: String, contentJson: String): String =
        sendStateEventResult(roomId, eventType, stateKey, contentJson)

    override suspend fun sendRoomEvent(roomId: String, eventType: String, contentJson: String): String = sendRoomEventResult(roomId, eventType, contentJson)

    override suspend fun redactEvent(roomId: String, eventId: String, reason: String?) = redactEventResult(roomId, eventId, reason)

    override fun subscribeRoom(roomId: String, subjects: FfiRoomSubjects, sink: RoomSink): BackendSubscription {
        roomSubscriptions += roomId to subjects
        return NoOpSubscription
    }

    override fun subscribeToDevice(eventTypes: List<String>, sink: ToDeviceSink): BackendSubscription {
        toDeviceSubscriptions += eventTypes
        return NoOpSubscription
    }

    override suspend fun relations(roomId: String, eventId: String, relType: String, eventType: String): List<FfiEventIn> = emptyList()

    override suspend fun openidToken(): FfiOpenIdToken = openidTokenResult()

    override suspend fun rtcTransports(): String = rtcTransportsResult()

    private object NoOpSubscription : BackendSubscription {
        override fun cancel() = Unit
    }
}
