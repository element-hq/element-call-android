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
import io.element.android.call.api.rtc.MatrixRtcCall
import io.element.android.call.api.rtc.MatrixRtcCallIntent
import io.element.android.call.api.rtc.MatrixRtcMembershipFormat
import io.element.android.call.api.rtc.MatrixRtcNotificationType
import io.element.android.call.api.rtc.MatrixRtcNotify
import io.element.android.call.api.rtc.MatrixRtcRoom
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.impl.util.childScope
import io.element.android.call.impl.util.runCatchingExceptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.matrix.rtc.FfiElementCallCompat
import org.matrix.rtc.FfiJoinSessionParams
import org.matrix.rtc.FfiNotificationType
import org.matrix.rtc.FfiNotifyConfig
import org.matrix.rtc.FfiTransportConfig
import org.matrix.rtc.RtcSessionManagerHandle
import timber.log.Timber

/**
 * One room open for MatrixRTC over the session-wide core, through the [matrixRoom] bridge opened for it.
 */
internal class RustMatrixRtcRoom(
    private val matrixRoom: ElementCallMatrixRoom,
    private val format: MatrixRtcMembershipFormat,
    private val manager: RtcSessionManagerHandle,
    private val transport: ElementCallMatrixTransport,
    private val transportDiscovery: RtcTransportDiscovery,
    private val dispatchers: ElementCallDispatchers,
    private val ffiDispatcher: CoroutineDispatcher,
    private val context: Context,
    private val sessionCoroutineScope: CoroutineScope,
    /** Closes [matrixRoom]. Runs after the leave: the core cancels the delayed event through the bridge. */
    private val onShutdown: suspend () -> Unit,
) : MatrixRtcRoom {
    override val roomId: RoomId = matrixRoom.roomId

    private val mutex = Mutex()
    private var call: RustMatrixRtcCall? = null
    private var isShutdown = false

    override suspend fun joinCall(applicationSlotId: String?, notify: MatrixRtcNotify?): Result<MatrixRtcCall> = runCatchingExceptions {
        mutex.withLock {
            check(!isShutdown) { "Room $roomId is shut down" }
            check(call == null) { "Already joined a call in $roomId" }
            join(applicationSlotId, notify).also { call = it }
        }
    }

    override suspend fun shutdown() {
        val joined = mutex.withLock {
            if (isShutdown) return
            isShutdown = true
            call.also { call = null }
        }
        joined?.leave(null)
        joined?.close()
        onShutdown()
    }

    private suspend fun join(applicationSlotId: String?, notify: MatrixRtcNotify?): RustMatrixRtcCall {
        val liveKit = transportDiscovery.discover()
            .map { transports ->
                Timber.i("MatrixRTC: discovered transports=$transports")
                transports.filterIsInstance<RtcTransport.LiveKit>().firstOrNull()
            }
            .getOrElse { throw IllegalStateException("Transport discovery failed: ${it.message}", it) }
            ?: error("Homeserver offers no LiveKit transport")
        // The 0.4 core maps the `ROOM` slot to Element Call's legacy room-wide call.
        val slotId = "$CALL_APPLICATION#${applicationSlotId ?: "ROOM"}"

        // One scope per call: cancelling it stops every feed loop for that call at once.
        val scope = sessionCoroutineScope.childScope(dispatchers.io, "MatrixRtcCall-$roomId-$slotId")

        // The feeder writes it after every membership feed, the call exposes it.
        val memberCount = MutableStateFlow(0)

        val feeder = RoomStateFeeder(
            manager = manager,
            room = matrixRoom,
            ownUserId = transport.userId,
            scope = scope,
            // Feeding one format and joining in another is not an error but a silence: a call that
            // connects and in which nobody appears.
            membershipFormat = format,
            slotId = slotId,
            memberCount = memberCount,
        )

        val memberId: String = withContext(ffiDispatcher) {
            // Room members and encryption first: without them the core excludes every membership
            // candidate as `SenderNotInRoom`. Memberships themselves come after the join, which is
            // what fixes the format they are read in - see RoomStateFeeder.start.
            feeder.start()

            // The core mints the member id: deriving one locally is what MSC4143 forbids.
            manager.join(
                FfiJoinSessionParams(
                    userId = transport.userId.value,
                    deviceId = transport.deviceId.value,
                    roomId = roomId.value,
                    slotId = slotId,
                    application = CALL_APPLICATION,
                    transport = FfiTransportConfig(type = LIVEKIT, livekitServiceUrl = liveKit.serviceUrl),
                    canSubscribe = CAN_SUBSCRIBE,
                    keepAliveTimeoutMs = KEEP_ALIVE_TIMEOUT_MS,
                    // Both nulls defer to the core, which hands us the sticky duration with each send.
                    stickyDurationMs = null,
                    encryptionConfig = null,
                    elementCallCompat = format.toFfi(),
                    // The core suppresses the notification anyway once anyone else is in the call.
                    notify = notify?.toFfi(),
                )
            )
        }
        Timber.d(
            "MatrixRTC: joined $roomId/$slotId, member id $memberId, format $format" +
                ", notify ${notify?.let { "${it.type} (${it.intent})" } ?: "none"}"
        )

        return RustMatrixRtcCall(
            roomId = roomId,
            slotId = slotId,
            memberId = memberId,
            liveKit = liveKit,
            manager = manager,
            transport = transport,
            callScope = scope,
            dispatchers = dispatchers,
            ffiDispatcher = ffiDispatcher,
            context = context,
            memberCount = memberCount,
        ).also {
            // Subscribed before a single membership is fed, and `start` suspends until it is: the
            // subscription reports only what changes after it exists.
            it.start()
            feeder.startMemberships()
        }
    }

    private fun MatrixRtcMembershipFormat.toFfi(): FfiElementCallCompat = when (this) {
        MatrixRtcMembershipFormat.CURRENT -> FfiElementCallCompat.OFF
        MatrixRtcMembershipFormat.STICKY2025 -> FfiElementCallCompat.STICKY_EVENTS
        MatrixRtcMembershipFormat.ROOM_STATE -> FfiElementCallCompat.STATE_EVENTS
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
        const val CALL_APPLICATION = "m.call"
        const val LIVEKIT = "livekit"
        val CAN_SUBSCRIBE = listOf(LIVEKIT)

        /** How long the core waits before considering a silent member gone. */
        const val KEEP_ALIVE_TIMEOUT_MS = 20_000uL
    }
}
