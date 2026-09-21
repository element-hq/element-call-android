/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.matrix

import io.element.android.call.api.ElementCallDispatchers
import io.element.android.call.api.matrix.ElementCallDelayedEventAction
import io.element.android.call.api.matrix.ElementCallMatrixRoom
import io.element.android.call.api.matrix.ElementCallRoomStateEvent
import io.element.android.call.api.matrix.ElementCallStickyEvent
import io.element.android.call.api.rtc.id.EventId
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.api.rtc.id.UserId
import io.element.android.call.matrix.temporary.widget.WidgetMatrixBridge
import io.element.android.call.matrix.util.runCatchingExceptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.MembershipState
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomInfo
import timber.log.Timber
import uniffi.matrix_sdk_base.EncryptionState

/**
 * One joined SDK [Room] as the call sees it.
 *
 * What the released bindings expose goes straight to them: the state event, the room info, the members.
 * What they do not - delayed events, sticky events, the room-state feed - goes through the widget-driver
 * [bridge], which is the temporary part; when the bindings catch up the bridge goes and the methods
 * below call the SDK instead, with nothing in `call/impl` learning about it. The bridge is for what the
 * SDK lacks and nothing else. A redaction goes to the SDK; the message-like room event goes through the
 * bridge only because `Room.sendRaw` returns no event id and the core needs it to lower a raised hand
 * (`docs/FEEDBACK.md`, matrix-rust-sdk item 9) - it moves to the SDK the day `sendRaw` returns one.
 */
internal class SdkElementCallMatrixRoom(
    override val roomId: RoomId,
    private val room: Room,
    private val bridge: WidgetMatrixBridge,
    private val dispatchers: ElementCallDispatchers,
    private val onClose: () -> Unit,
) : ElementCallMatrixRoom {
    private val roomInfo: Flow<RoomInfo> = room.roomInfoUpdates(dispatchers)

    override val isEncrypted: Flow<Boolean> = roomInfo
        .mapNotNull {
            when (it.encryptionState) {
                EncryptionState.ENCRYPTED -> true
                EncryptionState.NOT_ENCRYPTED -> false
                // Not known yet: say nothing rather than guess.
                EncryptionState.UNKNOWN -> null
            }
        }
        .distinctUntilChanged()

    /**
     * Re-read whenever the joined count moves, which is the one signal the room info gives about the
     * member list. `members()` fetches from the homeserver when the store has not got them, which is
     * exactly what a room we are joined to but have never opened needs.
     */
    override val joinedMemberIds: Flow<List<UserId>> = roomInfo
        .map { it.joinedMembersCount }
        .distinctUntilChanged()
        .map { loadJoinedMemberIds() }
        .filter { it.isNotEmpty() }
        .distinctUntilChanged()

    private suspend fun loadJoinedMemberIds(): List<UserId> = withContext(dispatchers.io) {
        runCatchingExceptions {
            room.members().use { iterator ->
                iterator.nextChunk(iterator.len()).orEmpty()
                    .filter { it.membership is MembershipState.Join }
                    .mapNotNull { member -> runCatchingExceptions { UserId(member.userId) }.getOrNull() }
            }
        }.onFailure {
            Timber.w(it, "ElementCallMatrix: cannot load the members of $roomId")
        }.getOrDefault(emptyList())
    }

    override suspend fun sendStateEvent(eventType: String, stateKey: String, contentJson: String): Result<EventId> = withContext(dispatchers.io) {
        runCatchingExceptions { EventId(room.sendStateEventRaw(eventType, stateKey, contentJson)) }.mapSdkFailure()
    }

    override suspend fun sendRoomEvent(eventType: String, contentJson: String): Result<EventId> {
        return bridge.sendRoomEvent(eventType, contentJson)
    }

    override suspend fun redactEvent(eventId: EventId, reason: String?): Result<Unit> = withContext(dispatchers.io) {
        runCatchingExceptions { room.redact(eventId.value, reason) }.mapSdkFailure()
    }

    override suspend fun sendDelayedEvent(eventType: String, stateKey: String?, contentJson: String, delayMs: ULong): Result<String> {
        return bridge.sendDelayedEvent(eventType, stateKey, contentJson, delayMs)
    }

    override suspend fun updateDelayedEvent(delayId: String, action: ElementCallDelayedEventAction): Result<Unit> {
        return bridge.updateDelayedEvent(delayId, action)
    }

    override suspend fun sendStickyEvent(eventType: String, contentJson: String, durationMs: ULong): Result<String> {
        return bridge.sendStickyEvent(eventType, contentJson, durationMs)
    }

    override fun stickyEvents(): Flow<List<ElementCallStickyEvent>> = bridge.stickyEvents()

    override fun stateEvents(eventType: String): Flow<List<ElementCallRoomStateEvent>> = bridge.stateEvents(eventType)

    override suspend fun close() {
        bridge.stop()
        onClose()
        room.close()
    }
}
