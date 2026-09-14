/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.matrix

import io.element.android.call.api.ElementCallDispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomInfo
import org.matrix.rustcomponents.sdk.RoomInfoListener

/**
 * The room's info now and on every change, for as long as it is collected.
 *
 * The current value is sent first: the SDK's subscription only reports what changes *after* it exists,
 * and a room whose name never changes during a call would otherwise never be named.
 */
internal fun Room.roomInfoUpdates(dispatchers: ElementCallDispatchers): Flow<RoomInfo> = callbackFlow {
    val handle = subscribeToRoomInfoUpdates(
        object : RoomInfoListener {
            override fun call(roomInfo: RoomInfo) {
                trySend(roomInfo)
            }
        }
    )
    trySend(roomInfo())
    awaitClose {
        handle.cancel()
        handle.close()
    }
}.flowOn(dispatchers.io)
