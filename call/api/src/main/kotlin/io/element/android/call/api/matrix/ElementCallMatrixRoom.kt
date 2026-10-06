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
 * Every feed of sets ([stickyEvents], [stateEvents], [joinedMemberIds]) emits the current set first,
 * then the whole set again on every change. An empty set means there is none: the core waits for a
 * first emission of each before the room is ready, so a feed that holds back an empty set stalls it.
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
     * The user ids of the room's joined members, ourselves included, whole list per change. An
     * implementation that has not loaded the members yet emits nothing.
     */
    val joinedMemberIds: Flow<List<UserId>>

    /**
     * Send a state event with the given raw JSON content.
     * @return the event id the homeserver assigned.
     */
    suspend fun sendStateEvent(eventType: String, stateKey: String, contentJson: String): Result<EventId>

    /**
     * Send a message-like room event with the given raw JSON content, encrypted like any other event in an
     * encrypted room. The core sends its MSC4075 notification this way when the membership is room state
     * ([io.element.android.call.api.rtc.MatrixRtcMembershipFormat.ROOM_STATE]), so this is what makes a
     * call ring; it also carries reactions and raised hands, and a raised hand is lowered by redacting the id
     * returned here.
     * @return the event id the homeserver assigned.
     */
    suspend fun sendRoomEvent(eventType: String, contentJson: String): Result<EventId>

    /** Redact an event this device sent: the core lowers a raised hand by redacting its `m.reaction`. */
    suspend fun redactEvent(eventId: EventId, reason: String?): Result<Unit>

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

    /** The sticky events currently live in the room (MSC4354), as complete sets. */
    fun stickyEvents(): Flow<List<ElementCallRoomEvent>>

    /**
     * The room's current state events of [eventType], one per state key, as complete sets.
     *
     * @param eventType the wire type. Types ruma treats as aliases of one another (`m.call.member` and
     * `org.matrix.msc3401.call.member`) share one bucket whichever spelling is asked for.
     */
    fun stateEvents(eventType: String): Flow<List<ElementCallRoomEvent>>

    /**
     * Message-like events of [eventTypes] as they arrive in the timeline, decrypted: reactions and
     * raised hands. Not a set: each emission is the batch that just arrived.
     */
    fun timelineEvents(eventTypes: List<String>): Flow<List<ElementCallRoomEvent>>

    /** The id of each event redacted from now on: a lowered hand. */
    fun redactions(): Flow<EventId>

    /**
     * The events related to [eventId] by [relType] (`m.annotation`) and of [eventType], decrypted: the
     * reactions to a membership already in the room when we arrive.
     */
    suspend fun relations(eventId: EventId, relType: String, eventType: String): Result<List<ElementCallRoomEvent>>

    /** Release whatever the implementation holds for this room. In-flight requests fail, feeds complete. Idempotent. */
    suspend fun close()
}
