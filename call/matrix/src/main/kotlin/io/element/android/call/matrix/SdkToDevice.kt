/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.matrix

import io.element.android.call.api.ElementCallDispatchers
import io.element.android.call.api.matrix.ElementCallEventEncryptionInfo
import io.element.android.call.api.matrix.ElementCallToDeviceMessage
import io.element.android.call.api.rtc.id.DeviceId
import io.element.android.call.api.rtc.id.UserId
import io.element.android.call.matrix.util.runCatchingExceptions
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.EventEncryptionInfo
import org.matrix.rustcomponents.sdk.ShieldState
import org.matrix.rustcomponents.sdk.ToDeviceMessage
import org.matrix.rustcomponents.sdk.ToDeviceMessageListener
import timber.log.Timber
import uniffi.matrix_sdk_ui.TimelineEventShieldStateCode

/**
 * The to-device messages of [eventTypes] this client receives, for as long as it is collected: for the
 * whole Matrix session, not only while a call is running. An encrypted message arrives decrypted with its
 * encryption info; the SDK drops what it could not decrypt and its own crypto traffic.
 *
 * Unbounded, because every message counts - a dropped media key is a participant nobody can hear.
 */
internal fun Client.toDeviceMessageUpdates(eventTypes: Set<String>, dispatchers: ElementCallDispatchers): Flow<ElementCallToDeviceMessage> = callbackFlow {
    val handle = subscribeToCustomToDeviceMessages(
        eventTypes.toList(),
        object : ToDeviceMessageListener {
            override fun onMessage(message: ToDeviceMessage) {
                message.toElementCallToDeviceMessage()?.let { trySend(it) }
            }
        },
    )
    awaitClose {
        handle.cancel()
        handle.close()
    }
}
    .buffer(Channel.UNLIMITED)
    .flowOn(dispatchers.io)

/** One SDK to-device message in the port's shape, or null for one whose claimed sender is not a user id. */
internal fun ToDeviceMessage.toElementCallToDeviceMessage(): ElementCallToDeviceMessage? {
    val sender = runCatchingExceptions { UserId(senderId) }.getOrNull() ?: run {
        Timber.w("ElementCallMatrix: ignoring a $eventType to-device message with an invalid sender")
        return null
    }
    return ElementCallToDeviceMessage(
        eventType = eventType,
        senderId = sender,
        content = content,
        encryptionInfo = encryptionInfo?.toElementCallEventEncryptionInfo(),
    )
}

/**
 * The SDK's encryption info in the port's shape. An attested sender that is not a user id cannot be
 * vouched for, so the message is treated as having arrived in the clear, which the key path refuses.
 */
internal fun EventEncryptionInfo.toElementCallEventEncryptionInfo(): ElementCallEventEncryptionInfo? {
    val attestedSender = runCatchingExceptions { UserId(senderId) }.getOrNull() ?: return null
    return ElementCallEventEncryptionInfo(
        senderId = attestedSender,
        senderDeviceId = senderDeviceId?.let(::DeviceId),
        senderCurve25519Key = senderCurve25519Key,
        isSenderCrossSigned = shieldState.vouchesForSender(),
    )
}

/**
 * Whether the lax shield lets us vouch for the sender, which is what
 * [ElementCallEventEncryptionInfo.isSenderCrossSigned] means: false only when the cryptographic story is
 * wrong rather than unconfirmed. An unknown device or one whose authenticity is not guaranteed is
 * unconfirmed; an unsigned device, an identity that changed under us or a mismatched sender is wrong.
 */
internal fun ShieldState.vouchesForSender(): Boolean = when (this) {
    ShieldState.None -> true
    is ShieldState.Grey -> code !in DISQUALIFYING_SHIELDS
    is ShieldState.Red -> code !in DISQUALIFYING_SHIELDS
}

private val DISQUALIFYING_SHIELDS = setOf(
    TimelineEventShieldStateCode.UNSIGNED_DEVICE,
    TimelineEventShieldStateCode.VERIFICATION_VIOLATION,
    TimelineEventShieldStateCode.MISMATCHED_SENDER,
    TimelineEventShieldStateCode.SENT_IN_CLEAR,
)

/**
 * The port's user -> device -> content, as the SDK sends it: one content to a list of devices per user.
 * Recipients sharing a content go in one send; a content per device is one send per distinct content.
 */
internal fun Map<UserId, Map<DeviceId, String>>.groupedByContent(): Map<String, Map<String, List<String>>> {
    val byContent = mutableMapOf<String, MutableMap<String, MutableList<String>>>()
    for ((userId, devices) in this) {
        for ((deviceId, content) in devices) {
            byContent.getOrPut(content) { mutableMapOf() }.getOrPut(userId.value) { mutableListOf() } += deviceId.value
        }
    }
    return byContent
}

/** The SDK's failures of one send, user id -> device ids, dropping any it reports under an invalid user id. */
internal fun Map<String, List<String>>.toRecipients(): Map<UserId, List<DeviceId>> = entries.mapNotNull { (userId, deviceIds) ->
    runCatchingExceptions { UserId(userId) }.getOrNull()?.let { it to deviceIds.map(::DeviceId) }
}.toMap()
