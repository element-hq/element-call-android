/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrixrtc.impl.bridge

import io.element.android.libraries.matrix.api.core.DeviceId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import kotlinx.coroutines.flow.Flow

/**
 * The Matrix operations the RTC core needs for one room that the released Rust SDK bindings do not expose.
 *
 * Exactly the gap and nothing else: delayed events (MSC4140), sticky events (MSC4354), a room-state feed
 * with full events, and to-device messaging. Everything the SDK does expose - `sendRawStateEvent`, the
 * OpenID token, members, encryption state - stays on [io.element.android.libraries.matrix.api.room.JoinedRoom]
 * and [io.element.android.libraries.matrix.api.MatrixClient].
 *
 * Today the only implementation drives the SDK's widget machine in-process
 * ([io.element.android.libraries.matrixrtc.impl.bridge.widget.WidgetMatrixBridge]). Once the bindings gain
 * these entry points, an SDK-backed implementation replaces it without the consumers - the command
 * sender and the feeders - learning about the change. `FEEDBACK.md`, "Widget-driver stopgap", lists what
 * retires it.
 *
 * Failures are [MatrixRtcBridgeException]s, so a caller can tell a homeserver refusal from a bridge that
 * is not running.
 */
internal interface MatrixRtcRoomBridge {
    val roomId: RoomId

    /**
     * Bring the bridge up. Returns once it can carry requests, or fails with
     * [MatrixRtcBridgeException.NotRunning] / [MatrixRtcBridgeException.Timeout].
     */
    suspend fun start(): Result<Unit>

    /** Take the bridge down. In-flight requests fail, feeds complete. Idempotent. */
    suspend fun stop()

    /**
     * Send a delayed event (MSC4140). With a [stateKey] it is a state event, without one message-like.
     * @return the `delay_id` the homeserver assigned, for [updateDelayedEvent].
     */
    suspend fun sendDelayedEvent(eventType: String, stateKey: String?, contentJson: String, delayMs: ULong): Result<String>

    /** Cancel or restart a delayed event the homeserver is still holding (MSC4140). */
    suspend fun updateDelayedEvent(delayId: String, action: MatrixRtcDelayedEventAction): Result<Unit>

    /**
     * Send an encrypted to-device message to every listed device.
     * @param messages user id -> device id -> JSON content.
     * @return the recipients that were *not* served, user id -> device ids; empty means everyone was.
     */
    suspend fun sendToDeviceMessage(eventType: String, messages: Map<UserId, Map<DeviceId, String>>): Result<Map<UserId, List<DeviceId>>>

    /**
     * Send a sticky event (MSC4354).
     * @return the event id, or an empty string when the transport cannot report one.
     */
    suspend fun sendStickyEvent(eventType: String, contentJson: String, durationMs: ULong): Result<String>

    /** The sticky events currently live in the room, as complete snapshots. */
    fun stickyEvents(): Flow<List<MatrixRtcStickyEvent>>

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
    fun stateEvents(eventType: String): Flow<List<MatrixRtcRoomStateEvent>>

    /** To-device messages of the types the bridge receives, for as long as it runs. */
    fun toDeviceMessages(): Flow<MatrixRtcToDeviceMessage>
}
