/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import android.content.Context
import io.element.android.call.api.ElementCallDispatchers
import io.element.android.call.api.rtc.MatrixRtcClient
import io.element.android.call.api.rtc.MatrixRtcMembershipFormat
import io.element.android.call.api.rtc.MatrixRtcRoom
import io.element.android.call.api.rtc.MatrixRtcSignallingHealth
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.impl.util.runCatchingExceptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.matrix.rtc.FfiMembershipFormat
import org.matrix.rtc.FfiRoomOptions
import org.matrix.rtc.FfiSignallingHealth
import org.matrix.rtc.MatrixBackend
import org.matrix.rtc.RtcClient
import timber.log.Timber

/**
 * The RTC core for one Matrix session, over the host's [backend].
 *
 * [sessionCoroutineScope] lives as long as the Matrix session: the rooms open for a call hang off it.
 */
internal class RustMatrixRtcClient(
    private val backend: MatrixBackend,
    private val dispatchers: ElementCallDispatchers,
    /** Only reaches as far as the camera, via the room, the call and then the media session. */
    private val context: Context,
    private val sessionCoroutineScope: CoroutineScope,
) : MatrixRtcClient {
    /** FFI calls are started on one thread, so they reach the core in the order we make them, never from main. */
    private val ffiDispatcher = dispatchers.io.limitedParallelism(1)

    private var rtcClient: RtcClient? = null

    /** Built on first use: creating it loads the native library, and it does no I/O. */
    @Synchronized
    private fun rtcClient(): RtcClient = rtcClient ?: run {
        MatrixRtcFfi.ensureInitialized()
        RtcClient(backend).also { rtcClient = it }
    }

    override suspend fun room(roomId: RoomId, format: MatrixRtcMembershipFormat): Result<MatrixRtcRoom> = runCatchingExceptions {
        val rtcRoom = withTimeout(SEEDING_TIMEOUT_MS) {
            // The core resolves once every subject has delivered; one that never does would hang the join.
            withContext(ffiDispatcher) { rtcClient().room(roomId.value, FfiRoomOptions(format.toFfi())) }
        }
        Timber.i("MatrixRTC: $roomId open in $format")
        RustMatrixRtcRoom(
            rtcRoom = rtcRoom,
            roomId = roomId,
            dispatchers = dispatchers,
            ffiDispatcher = ffiDispatcher,
            context = context,
            sessionCoroutineScope = sessionCoroutineScope,
        )
    }

    override fun signallingHealth(): Flow<MatrixRtcSignallingHealth> = flow {
        val subscription = withContext(ffiDispatcher) { rtcClient().subscribeSignallingHealth() }
        try {
            while (true) {
                val health = runCatchingExceptions { subscription.next() }
                    .onFailure { Timber.w(it, "MatrixRTC: signalling health subscription failed") }
                    .getOrNull()
                    ?: break
                emit(health.map())
            }
        } finally {
            subscription.close()
        }
    }
        .distinctUntilChanged()
        .onEach { Timber.i("MatrixRTC: signalling $it") }

    override fun reportConnectivity(isOnline: Boolean) {
        Timber.i("MatrixRTC: the host reports the homeserver ${if (isOnline) "online" else "offline"}")
        sessionCoroutineScope.launch(ffiDispatcher) { rtcClient().reportConnectivity(isOnline) }
    }

    private fun FfiSignallingHealth.map(): MatrixRtcSignallingHealth = when (this) {
        is FfiSignallingHealth.Reachable -> MatrixRtcSignallingHealth.Reachable
        is FfiSignallingHealth.Unreachable -> MatrixRtcSignallingHealth.Unreachable(sinceMs.toLong())
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
