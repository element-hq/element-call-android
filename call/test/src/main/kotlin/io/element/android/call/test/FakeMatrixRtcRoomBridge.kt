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
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.tests.testutils.lambda.lambdaError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

internal class FakeMatrixRtcRoomBridge(
    override val roomId: RoomId = A_ROOM_ID,
    private val startResult: () -> Result<Unit> = { Result.success(Unit) },
    private val sendDelayedEventResult: (String, String?, String, ULong) -> Result<String> = { _, _, _, _ -> lambdaError() },
    private val updateDelayedEventResult: (String, MatrixRtcDelayedEventAction) -> Result<Unit> = { _, _ -> lambdaError() },
    private val sendToDeviceMessageResult: (String, Map<UserId, Map<DeviceId, String>>) -> Result<Map<UserId, List<DeviceId>>> =
        { _, _ -> lambdaError() },
    private val sendStickyEventResult: (String, String, ULong) -> Result<String> = { _, _, _ -> lambdaError() },
    private val stickyEvents: Flow<List<MatrixRtcStickyEvent>> = MutableStateFlow(emptyList()),
    // Named for the lambda convention rather than after the flow, because `stateEvents(eventType)` in
    // the override below would resolve to the override itself.
    private val stateEventsResult: (String) -> Flow<List<MatrixRtcRoomStateEvent>> = { MutableStateFlow(emptyList()) },
    private val toDeviceMessages: Flow<MatrixRtcToDeviceMessage> = emptyFlow(),
) : MatrixRtcRoomBridge {
    var startCalledCount = 0
        private set
    var stopCalledCount = 0
        private set

    override suspend fun start(): Result<Unit> {
        startCalledCount++
        return startResult()
    }

    override suspend fun stop() {
        stopCalledCount++
    }

    override suspend fun sendDelayedEvent(eventType: String, stateKey: String?, contentJson: String, delayMs: ULong): Result<String> {
        return sendDelayedEventResult(eventType, stateKey, contentJson, delayMs)
    }

    override suspend fun updateDelayedEvent(delayId: String, action: MatrixRtcDelayedEventAction): Result<Unit> {
        return updateDelayedEventResult(delayId, action)
    }

    override suspend fun sendToDeviceMessage(eventType: String, messages: Map<UserId, Map<DeviceId, String>>): Result<Map<UserId, List<DeviceId>>> {
        return sendToDeviceMessageResult(eventType, messages)
    }

    override suspend fun sendStickyEvent(eventType: String, contentJson: String, durationMs: ULong): Result<String> {
        return sendStickyEventResult(eventType, contentJson, durationMs)
    }

    override fun stickyEvents(): Flow<List<MatrixRtcStickyEvent>> = stickyEvents

    override fun stateEvents(eventType: String): Flow<List<MatrixRtcRoomStateEvent>> = stateEventsResult(eventType)

    override fun toDeviceMessages(): Flow<MatrixRtcToDeviceMessage> = toDeviceMessages
}
