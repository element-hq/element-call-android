/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.matrix

import io.element.android.call.api.ElementCallDispatchers
import io.element.android.call.api.matrix.ElementCallEventEncryptionInfo
import io.element.android.call.api.matrix.ElementCallRoomEvent
import io.element.android.call.api.rtc.id.DeviceId
import io.element.android.call.api.rtc.id.EventId
import io.element.android.call.api.rtc.id.UserId
import io.element.android.call.matrix.util.runCatchingExceptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.matrix.rustcomponents.sdk.EventEncryptionInfo
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.ShieldState
import org.matrix.rustcomponents.sdk.StickyEvent
import org.matrix.rustcomponents.sdk.StickyEventsListener
import org.matrix.rustcomponents.sdk.StickyEventsUpdate
import org.matrix.rustcomponents.sdk.StickyKey
import timber.log.Timber

/** The room's live sticky events as complete sets, the first one as soon as it is collected. */
internal fun Room.stickyEventSets(dispatchers: ElementCallDispatchers): Flow<List<ElementCallRoomEvent>> = callbackFlow {
    val set = StickyEventSet()
    val handle = subscribeToStickyEvents(
        object : StickyEventsListener {
            override fun onUpdate(update: StickyEventsUpdate) {
                trySend(set.apply(update))
            }
        }
    )
    awaitClose {
        handle.cancel()
        handle.close()
    }
}.flowOn(dispatchers.io)

/** Folds the SDK's resets and changes into the whole set, which is what the port hands the core. */
internal class StickyEventSet {
    private val live = LinkedHashMap<StickyKey, ElementCallRoomEvent>()

    fun apply(update: StickyEventsUpdate): List<ElementCallRoomEvent> {
        when (update) {
            is StickyEventsUpdate.Reset -> {
                live.clear()
                update.events.forEach { put(it) }
            }
            is StickyEventsUpdate.Changes -> {
                update.added.forEach { put(it) }
                update.updated.forEach { put(it) }
                update.removed.forEach { live.remove(it.key) }
            }
        }
        return live.values.toList()
    }

    private fun put(event: StickyEvent) {
        val roomEvent = event.toRoomEvent()
        if (roomEvent == null) {
            Timber.w("ElementCallMatrix: ignoring a malformed sticky event ${event.eventId}")
            live.remove(event.key)
        } else {
            live[event.key] = roomEvent
        }
    }
}

private val json = Json { ignoreUnknownKeys = true }

internal fun StickyEvent.toRoomEvent(): ElementCallRoomEvent? {
    val event = runCatchingExceptions { json.parseToJsonElement(eventJson) as? JsonObject }.getOrNull() ?: return null
    val content = event["content"] as? JsonObject ?: return null
    val timestampMs = (event["origin_server_ts"] as? JsonPrimitive)?.longOrNull ?: return null
    val encryption = encryptionInfo?.let { it.toElementCall() ?: return null }
    return ElementCallRoomEvent(
        eventId = runCatchingExceptions { EventId(eventId) }.getOrNull() ?: return null,
        sender = runCatchingExceptions { UserId(key.sender) }.getOrNull() ?: return null,
        // The type on the wire, which for an unstable type may differ from the key's.
        eventType = (event["type"] as? JsonPrimitive)?.contentOrNull ?: key.eventType,
        stateKey = null,
        timestampMs = timestampMs,
        contentJson = json.encodeToString(JsonObject.serializer(), content),
        encryptionInfo = encryption,
    )
}

/** Red is a broken cryptographic story, grey an unconfirmed one: only red refuses to vouch. */
private fun EventEncryptionInfo.toElementCall(): ElementCallEventEncryptionInfo? {
    return ElementCallEventEncryptionInfo(
        senderId = runCatchingExceptions { UserId(senderId) }.getOrNull() ?: return null,
        senderDeviceId = senderDeviceId?.let(::DeviceId),
        senderCurve25519Key = senderCurve25519Key,
        isSenderCrossSigned = when (shieldState) {
            is ShieldState.Red -> false
            is ShieldState.Grey -> null
            ShieldState.None -> true
        },
    )
}
