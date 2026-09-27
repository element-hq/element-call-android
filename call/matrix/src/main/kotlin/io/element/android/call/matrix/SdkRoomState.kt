/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.matrix

import io.element.android.call.api.ElementCallDispatchers
import io.element.android.call.api.matrix.ElementCallRoomStateEvent
import io.element.android.call.api.rtc.id.EventId
import io.element.android.call.api.rtc.id.UserId
import io.element.android.call.matrix.util.runCatchingExceptions
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomStateEvent
import org.matrix.rustcomponents.sdk.RoomStateEventsListener
import timber.log.Timber
import uniffi.ruma_events.stateEventTypeFromString

/**
 * The room's current state events of [eventType], one per state key, now and after every sync that
 * changes any of them, for as long as it is collected.
 *
 * The SDK calls the listener at once with the current list, then with the whole list again per sync, so
 * each emission is complete and only the latest matters. Never an empty list: the feeder reads that as
 * "not synced yet". Ruma treats `m.call.member` as an alias of `org.matrix.msc3401.call.member`, so one
 * subscription on either spelling carries both.
 *
 * Only the state the sync asks for is stored locally; the call member type is in sliding sync's default
 * `required_state`, so it is there.
 */
internal fun Room.stateEventUpdates(eventType: String, dispatchers: ElementCallDispatchers): Flow<List<ElementCallRoomStateEvent>> = callbackFlow {
    val handle = subscribeToStateEvents(
        stateEventTypeFromString(eventType),
        object : RoomStateEventsListener {
            override fun onUpdate(events: List<RoomStateEvent>) {
                trySend(events.mapNotNull { it.toElementCallRoomStateEvent(eventType) })
            }
        },
    )
    awaitClose {
        handle.cancel()
        handle.close()
    }
}
    .buffer(Channel.CONFLATED)
    .filter { it.isNotEmpty() }
    .flowOn(dispatchers.io)

/**
 * One SDK state event in the port's shape, or null for one that cannot be addressed.
 *
 * The type is the one subscribed to rather than the event's own: the SDK hands it back as an opaque
 * `StateEventType` whose custom variant has no readable string, and the subscribed type is what the
 * feeder partitions on. The stripped state of a room we are only invited to has no event id; it is kept,
 * as the port allows, though a call never reads an invited room.
 */
internal fun RoomStateEvent.toElementCallRoomStateEvent(eventType: String): ElementCallRoomStateEvent? {
    val sender = runCatchingExceptions { UserId(sender) }.getOrNull() ?: run {
        Timber.w("ElementCallMatrix: ignoring a $eventType state event with an invalid sender")
        return null
    }
    return ElementCallRoomStateEvent(
        eventType = eventType,
        stateKey = stateKey,
        sender = sender,
        contentJson = contentJson,
        eventId = eventId?.let { runCatchingExceptions { EventId(it) }.getOrNull() },
        timestampMs = timestamp?.toLong(),
    )
}
