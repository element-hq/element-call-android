/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.services

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallData
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.test.A_ROOM_ID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ElementCallForegroundServiceTest {
    @Test
    fun `muting and unmuting re-post the notification`() = runTest {
        val state = MutableStateFlow<ElementCallSnapshot?>(aCall())

        state.notificationContentChanges().test {
            state.value = aCall(isMicrophoneMuted = true)
            assertThat(awaitItem()).isEqualTo(NotificationContent(roomName = A_ROOM_NAME, isMuted = true))
            state.value = aCall(isMicrophoneMuted = false)
            assertThat(awaitItem()).isEqualTo(NotificationContent(roomName = A_ROOM_NAME, isMuted = false))
        }
    }

    @Test
    fun `a room name change re-posts the notification`() = runTest {
        val state = MutableStateFlow<ElementCallSnapshot?>(aCall(roomName = null))

        state.notificationContentChanges().test {
            state.value = aCall()
            assertThat(awaitItem()).isEqualTo(NotificationContent(roomName = A_ROOM_NAME, isMuted = false))
        }
    }

    @Test
    fun `changes the notification does not show are ignored`() = runTest {
        val state = MutableStateFlow<ElementCallSnapshot?>(aCall(connection = ElementCallConnection.Joining))

        state.notificationContentChanges().test {
            state.value = aCall(connection = ElementCallConnection.Connected)
            state.value = null
            expectNoEvents()
        }
    }

    private fun aCall(
        roomName: String? = A_ROOM_NAME,
        isMicrophoneMuted: Boolean = false,
        connection: ElementCallConnection = ElementCallConnection.Connected,
    ) = ElementCallSnapshot(
        callData = ElementCallData(roomId = A_ROOM_ID, isAudioCall = false),
        connection = connection,
        roomName = roomName,
        isMicrophoneMuted = isMicrophoneMuted,
    )
}

private const val A_ROOM_NAME = "Design"
