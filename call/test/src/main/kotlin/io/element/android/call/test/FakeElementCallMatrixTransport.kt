/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.test

import io.element.android.call.api.matrix.ElementCallMatrixRoom
import io.element.android.call.api.matrix.ElementCallMatrixTransport
import io.element.android.call.api.matrix.ElementCallOpenIdToken
import io.element.android.call.api.matrix.ElementCallToDeviceMessage
import io.element.android.call.api.rtc.id.DeviceId
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.api.rtc.id.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter

class FakeElementCallMatrixTransport(
    override val userId: UserId = A_USER_ID,
    override val deviceId: DeviceId = A_DEVICE_ID,
    override val homeserverUrl: String = A_HOMESERVER_URL,
    private val serverName: String = A_SERVER_NAME,
    private val getUrlResult: (String) -> Result<String> = { lambdaError() },
    private val getOpenIdTokenResult: () -> Result<ElementCallOpenIdToken> = { lambdaError() },
    private val openRoomResult: (RoomId) -> Result<ElementCallMatrixRoom> = { lambdaError() },
    private val sendToDeviceMessageResult: (String, Map<UserId, Map<DeviceId, String>>) -> Result<Map<UserId, List<DeviceId>>> =
        { _, _ -> lambdaError() },
) : ElementCallMatrixTransport {
    // Buffered so a test can publish before anything collects; nothing is replayed to a late subscriber,
    // which is also what the real thing does.
    private val toDevice = MutableSharedFlow<ElementCallToDeviceMessage>(extraBufferCapacity = 64)

    override fun userIdServerName(): String = serverName

    override suspend fun getUrl(url: String): Result<String> = getUrlResult(url)

    override suspend fun getOpenIdToken(): Result<ElementCallOpenIdToken> = getOpenIdTokenResult()

    override suspend fun openRoom(roomId: RoomId): Result<ElementCallMatrixRoom> = openRoomResult(roomId)

    override fun toDeviceMessages(eventTypes: Set<String>): Flow<ElementCallToDeviceMessage> {
        return toDevice.filter { it.eventType in eventTypes }
    }

    override suspend fun sendToDeviceMessage(eventType: String, messages: Map<UserId, Map<DeviceId, String>>): Result<Map<UserId, List<DeviceId>>> {
        return sendToDeviceMessageResult(eventType, messages)
    }

    /** A to-device message arrives. */
    suspend fun givenToDeviceMessage(message: ElementCallToDeviceMessage) {
        toDevice.emit(message)
    }
}
