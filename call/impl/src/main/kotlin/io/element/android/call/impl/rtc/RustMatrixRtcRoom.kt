/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import android.content.Context
import io.element.android.call.api.ElementCallDispatchers
import io.element.android.call.api.rtc.MatrixRtcCall
import io.element.android.call.api.rtc.MatrixRtcCallIntent
import io.element.android.call.api.rtc.MatrixRtcNotificationType
import io.element.android.call.api.rtc.MatrixRtcNotify
import io.element.android.call.api.rtc.MatrixRtcRoom
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.impl.util.childScope
import io.element.android.call.impl.util.runCatchingExceptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.matrix.rtc.FfiJoinSessionParams
import org.matrix.rtc.FfiJoinTransport
import org.matrix.rtc.FfiNotificationType
import org.matrix.rtc.FfiNotifyConfig
import org.matrix.rtc.RtcRoom
import timber.log.Timber

/** One room open in the core. */
internal class RustMatrixRtcRoom(
    private val rtcRoom: RtcRoom,
    override val roomId: RoomId,
    private val dispatchers: ElementCallDispatchers,
    private val ffiDispatcher: CoroutineDispatcher,
    private val context: Context,
    private val sessionCoroutineScope: CoroutineScope,
    /** Closes the room's bridge. Runs after the core's shutdown, which leaves through it. */
    private val onShutdown: suspend () -> Unit,
) : MatrixRtcRoom {
    private val mutex = Mutex()
    private var call: RustMatrixRtcCall? = null
    private var isShutdown = false

    override suspend fun joinCall(applicationSlotId: String?, notify: MatrixRtcNotify?): Result<MatrixRtcCall> = runCatchingExceptions {
        mutex.withLock {
            check(!isShutdown) { "Room $roomId is shut down" }
            val rtcCall = withContext(ffiDispatcher) {
                rtcRoom.joinCall(
                    FfiJoinSessionParams(
                        applicationSlotId = applicationSlotId,
                        // The core picks from the backend's `rtcTransports`, and fails the join when there is none.
                        transport = FfiJoinTransport.Advertised,
                        keepAliveTimeoutMs = KEEP_ALIVE_TIMEOUT_MS,
                        // Nulls defer to the core and to the slot.
                        stickyDurationMs = null,
                        degradedLifetimeMs = null,
                        encryptionConfig = null,
                        notify = notify?.toFfi(),
                        reactions = null,
                    )
                )
            }
            Timber.d(
                "MatrixRTC: joined $roomId/${rtcCall.slotId()}, member id ${rtcCall.memberId()}" +
                    ", notify ${notify?.let { "${it.type} (${it.intent})" } ?: "none"}"
            )
            RustMatrixRtcCall(
                rtcCall = rtcCall,
                roomId = roomId,
                callScope = sessionCoroutineScope.childScope(dispatchers.io, "MatrixRtcCall-$roomId"),
                dispatchers = dispatchers,
                ffiDispatcher = ffiDispatcher,
                context = context,
            ).also {
                call = it
                it.start()
            }
        }
    }

    override suspend fun shutdown() {
        val joined = mutex.withLock {
            if (isShutdown) return
            isShutdown = true
            call.also { call = null }
        }
        // The core leaves whatever is still joined, through the bridge, before the bridge goes.
        withContext(ffiDispatcher) { rtcRoom.shutdown() }
        joined?.close()
        rtcRoom.close()
        onShutdown()
        Timber.i("MatrixRTC: $roomId shut down")
    }

    private fun MatrixRtcNotify.toFfi(): FfiNotifyConfig = FfiNotifyConfig(
        notificationType = when (type) {
            MatrixRtcNotificationType.RING -> FfiNotificationType.RING
            // The core spells the silent one NOTIFICATION where the receive side says NOTIFY.
            MatrixRtcNotificationType.NOTIFY -> FfiNotificationType.NOTIFICATION
        },
        // Lowercase because it goes out verbatim as `m.call.intent`.
        intent = intent?.toWire(),
        lifetimeMs = lifetimeMs,
        mentionUserIds = mentionUserIds.map { it.value },
        mentionRoom = mentionRoom,
    )

    private fun MatrixRtcCallIntent.toWire(): String = when (this) {
        MatrixRtcCallIntent.AUDIO -> "audio"
        MatrixRtcCallIntent.VIDEO -> "video"
    }

    private companion object {
        /** How long the core waits before considering a silent member gone. */
        const val KEEP_ALIVE_TIMEOUT_MS = 20_000uL
    }
}
