/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.test

import io.element.android.call.api.matrix.ElementCallDelayedEventAction
import io.element.android.call.api.matrix.ElementCallMatrixRoom
import io.element.android.call.api.matrix.ElementCallRoomStateEvent
import io.element.android.call.api.matrix.ElementCallStickyEvent
import io.element.android.call.api.rtc.id.EventId
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.api.rtc.id.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

class FakeElementCallMatrixRoom(
    override val roomId: RoomId = A_ROOM_ID,
    /** Encrypted, and known to be, unless a test says otherwise. */
    override val isEncrypted: Flow<Boolean> = flowOf(true),
    /** Ourselves, so a membership feed that waits for the members is not held up by default. */
    override val joinedMemberIds: Flow<List<UserId>> = flowOf(listOf(A_USER_ID)),
    private val sendStateEventResult: (String, String, String) -> Result<EventId> = { _, _, _ -> lambdaError() },
    private val sendDelayedEventResult: (String, String?, String, ULong) -> Result<String> = { _, _, _, _ -> lambdaError() },
    private val updateDelayedEventResult: (String, ElementCallDelayedEventAction) -> Result<Unit> = { _, _ -> lambdaError() },
    private val sendStickyEventResult: (String, String, ULong) -> Result<String> = { _, _, _ -> lambdaError() },
    private val stickyEvents: Flow<List<ElementCallStickyEvent>> = MutableStateFlow(emptyList()),
    // Named for the lambda convention rather than after the flow, because `stateEvents(eventType)` in
    // the override below would resolve to the override itself.
    private val stateEventsResult: (String) -> Flow<List<ElementCallRoomStateEvent>> = { MutableStateFlow(emptyList()) },
) : ElementCallMatrixRoom {
    var closeCalledCount = 0
        private set

    override suspend fun sendStateEvent(eventType: String, stateKey: String, contentJson: String): Result<EventId> {
        return sendStateEventResult(eventType, stateKey, contentJson)
    }

    override suspend fun sendDelayedEvent(eventType: String, stateKey: String?, contentJson: String, delayMs: ULong): Result<String> {
        return sendDelayedEventResult(eventType, stateKey, contentJson, delayMs)
    }

    override suspend fun updateDelayedEvent(delayId: String, action: ElementCallDelayedEventAction): Result<Unit> {
        return updateDelayedEventResult(delayId, action)
    }

    override suspend fun sendStickyEvent(eventType: String, contentJson: String, durationMs: ULong): Result<String> {
        return sendStickyEventResult(eventType, contentJson, durationMs)
    }

    override fun stickyEvents(): Flow<List<ElementCallStickyEvent>> = stickyEvents

    override fun stateEvents(eventType: String): Flow<List<ElementCallRoomStateEvent>> = stateEventsResult(eventType)

    override suspend fun close() {
        closeCalledCount++
    }
}
