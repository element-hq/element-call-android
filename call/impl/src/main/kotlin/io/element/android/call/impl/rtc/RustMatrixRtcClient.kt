/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import android.content.Context
import io.element.android.call.api.ElementCallDispatchers
import io.element.android.call.api.matrix.ElementCallMatrixRoom
import io.element.android.call.api.matrix.ElementCallMatrixTransport
import io.element.android.call.api.rtc.MatrixRtcClient
import io.element.android.call.api.rtc.MatrixRtcMembershipFormat
import io.element.android.call.api.rtc.MatrixRtcRoom
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.impl.util.runCatchingExceptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.matrix.rtc.FfiMembershipFormat
import org.matrix.rtc.FfiRoomOptions
import org.matrix.rtc.RtcClient
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * The RTC core for one Matrix session, over a [DefaultMatrixBackend] on [transport].
 *
 * [sessionCoroutineScope] lives as long as the Matrix session: the backend's subscriptions and the
 * rooms open for a call all hang off it.
 */
internal class RustMatrixRtcClient(
    private val transport: ElementCallMatrixTransport,
    private val dispatchers: ElementCallDispatchers,
    /** Only reaches as far as the camera, via the room, the call and then the media session. */
    private val context: Context,
    private val sessionCoroutineScope: CoroutineScope,
) : MatrixRtcClient {
    /** FFI calls are started on one thread, so they reach the core in the order we make them, never from main. */
    private val ffiDispatcher = dispatchers.io.limitedParallelism(1)

    /** The bridges open for a room, by id: the backend routes the core's room-scoped calls through them. */
    private val openRooms = ConcurrentHashMap<RoomId, ElementCallMatrixRoom>()

    private val backend = DefaultMatrixBackend(
        transport = transport,
        transportDiscovery = RtcTransportDiscovery(transport),
        roomProvider = { openRooms[it] },
        scope = sessionCoroutineScope,
        ioDispatcher = dispatchers.io,
    )

    private var rtcClient: RtcClient? = null

    /** Built on first use: creating it loads the native library, and it does no I/O. */
    @Synchronized
    private fun rtcClient(): RtcClient = rtcClient ?: run {
        MatrixRtcFfi.ensureInitialized()
        RtcClient(backend).also { rtcClient = it }
    }

    override suspend fun room(roomId: RoomId, format: MatrixRtcMembershipFormat): Result<MatrixRtcRoom> = runCatchingExceptions {
        // Registered before the core subscribes, which it does from inside `room`.
        val matrixRoom = transport.openRoom(roomId).getOrThrow()
        openRooms.put(roomId, matrixRoom)?.let { Timber.w("MatrixRTC: replacing the open room for $roomId") }
        val onShutdown: suspend () -> Unit = {
            openRooms.remove(roomId, matrixRoom)
            matrixRoom.close()
        }
        val rtcRoom = runCatchingExceptions {
            // The core resolves once every subject has delivered; one that never does would hang the join.
            withTimeout(SEEDING_TIMEOUT_MS) {
                withContext(ffiDispatcher) { rtcClient().room(roomId.value, FfiRoomOptions(format.toFfi())) }
            }
        }.onFailure { onShutdown() }.getOrThrow()
        Timber.i("MatrixRTC: $roomId open in $format")
        RustMatrixRtcRoom(
            rtcRoom = rtcRoom,
            roomId = roomId,
            dispatchers = dispatchers,
            ffiDispatcher = ffiDispatcher,
            context = context,
            sessionCoroutineScope = sessionCoroutineScope,
            onShutdown = onShutdown,
        )
    }

    private fun MatrixRtcMembershipFormat.toFfi(): FfiMembershipFormat = when (this) {
        MatrixRtcMembershipFormat.CURRENT -> FfiMembershipFormat.CURRENT
        MatrixRtcMembershipFormat.STICKY2025 -> FfiMembershipFormat.STICKY2025
        MatrixRtcMembershipFormat.ROOM_STATE -> FfiMembershipFormat.ROOM_STATE
    }

    private companion object {
        /** The core logs which subject it is still waiting on; this turns a hang into a failure. */
        const val SEEDING_TIMEOUT_MS = 30_000L
    }
}
