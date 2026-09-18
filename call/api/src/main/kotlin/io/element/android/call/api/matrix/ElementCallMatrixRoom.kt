/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.matrix

import io.element.android.call.api.rtc.id.EventId
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.api.rtc.id.UserId
import kotlinx.coroutines.flow.Flow

/**
 * The Matrix operations the RTC core needs for one room while a call is running in it: what the
 * call publishes to the room, and what it reads back from it.
 *
 * Obtained from [ElementCallMatrixTransport.openRoom] and given back with [close] once the call has
 * left. The methods the released Rust SDK does not expose - delayed events (MSC4140), sticky events
 * (MSC4354), a room-state feed with full events - are today carried by the widget-driver stopgap in
 * `element-call-matrix`; when the SDK gains them, that implementation changes and this interface does
 * not.
 *
 * Failures are [ElementCallMatrixException]s, so a caller can tell a homeserver refusal from a room
 * that is not open.
 */
interface ElementCallMatrixRoom {
    val roomId: RoomId

    /**
     * Whether the room is encrypted, emitted once known and again on change. Never a guess: the RTC
     * core treats cleartext membership as valid in an unencrypted room, so "unknown" must not be
     * reported as false.
     */
    val isEncrypted: Flow<Boolean>

    /**
     * The user ids of the room's joined members, whole list per change. Never empty for a room the
     * session is joined to; an implementation that has not loaded the members yet emits nothing.
     */
    val joinedMemberIds: Flow<List<UserId>>

    /**
     * Send a state event with the given raw JSON content.
     * @return the event id the homeserver assigned.
     */
    suspend fun sendStateEvent(eventType: String, stateKey: String, contentJson: String): Result<EventId>

    /**
     * Send a delayed event (MSC4140). With a [stateKey] it is a state event, without one message-like.
     * @return the `delay_id` the homeserver assigned, for [updateDelayedEvent].
     */
    suspend fun sendDelayedEvent(eventType: String, stateKey: String?, contentJson: String, delayMs: ULong): Result<String>

    /** Cancel or restart a delayed event the homeserver is still holding (MSC4140). */
    suspend fun updateDelayedEvent(delayId: String, action: ElementCallDelayedEventAction): Result<Unit>

    /**
     * Send a sticky event (MSC4354).
     * @return the event id, or an empty string when the transport cannot report one.
     */
    suspend fun sendStickyEvent(eventType: String, contentJson: String, durationMs: ULong): Result<String>

    /** The sticky events currently live in the room, as complete snapshots. */
    fun stickyEvents(): Flow<List<ElementCallStickyEvent>>

    /**
     * The room's current state events of [eventType], one per state key, as complete snapshots.
     *
     * A subscriber is handed the current state right away when there is any, then the whole state again
     * on every change. An empty snapshot is never emitted: room state is replaced, never removed, so a
     * departure is a present event with `{}` content, and an empty list can only mean "not synced yet".
     *
     * @param eventType the wire type. Types ruma treats as aliases of one another (`m.call.member` and
     * `org.matrix.msc3401.call.member`) share one bucket whichever spelling is asked for.
     */
    fun stateEvents(eventType: String): Flow<List<ElementCallRoomStateEvent>>

    /** Release whatever the implementation holds for this room. In-flight requests fail, feeds complete. Idempotent. */
    suspend fun close()
}
