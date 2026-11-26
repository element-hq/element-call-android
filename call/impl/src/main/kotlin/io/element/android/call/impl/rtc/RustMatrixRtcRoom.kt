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
import io.element.android.call.api.rtc.MatrixRtcCallKind
import io.element.android.call.api.rtc.MatrixRtcGroupNotify
import io.element.android.call.api.rtc.MatrixRtcJoinException
import io.element.android.call.api.rtc.MatrixRtcRing
import io.element.android.call.api.rtc.MatrixRtcRoom
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.impl.util.childScope
import io.element.android.call.impl.util.runCatchingExceptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.matrix.rtc.FfiCallIntent
import org.matrix.rtc.FfiCallKind
import org.matrix.rtc.FfiGroupNotify
import org.matrix.rtc.FfiJoinSessionParams
import org.matrix.rtc.FfiJoinTransport
import org.matrix.rtc.FfiRingConfig
import org.matrix.rtc.MatrixRtcFfiException
import org.matrix.rtc.RtcRoom
import timber.log.Timber

/**
 * The full slot id the core's room reads take: `m.call#` and the application slot, `room` when null, the room-wide call
 * a join with no slot joins (MSC4143). Lower case: the core maps Element Call's `m.call#ROOM` to it (feature-hq 045 C8).
 */
internal fun callSlotId(applicationSlotId: String?): String = "m.call#${applicationSlotId ?: "room"}"

/** One room open in the core. */
internal class RustMatrixRtcRoom(
    private val rtcRoom: RtcRoom,
    override val roomId: RoomId,
    private val dispatchers: ElementCallDispatchers,
    private val ffiDispatcher: CoroutineDispatcher,
    private val context: Context,
    private val sessionCoroutineScope: CoroutineScope,
) : MatrixRtcRoom {
    private val mutex = Mutex()
    private var call: RustMatrixRtcCall? = null
    private var isShutdown = false

    override suspend fun joinCall(
        kind: MatrixRtcCallKind,
        intent: MatrixRtcCallIntent,
        applicationSlotId: String?,
    ): Result<MatrixRtcCall> = runCatchingExceptions {
        mutex.withLock {
            check(!isShutdown) { "Room $roomId is shut down" }
            val rtcCall = withContext(ffiDispatcher) {
                joinCallTyped(
                    FfiJoinSessionParams(
                        applicationSlotId = applicationSlotId,
                        // The core picks from the backend's `rtcTransports`, and fails the join when there is none.
                        transport = FfiJoinTransport.Advertised,
                        keepAliveTimeoutMs = KEEP_ALIVE_TIMEOUT_MS,
                        // Nulls defer to the core and to the slot.
                        stickyDurationMs = null,
                        degradedLifetimeMs = null,
                        encryptionConfig = null,
                        kind = kind.toFfi(),
                        reactions = null,
                        // On every join, notifying or not, so the core does not settle it again (feature-hq 045 C6).
                        intent = intent.toFfi(),
                    )
                )
            }
            Timber.d(
                "MatrixRTC: joined $roomId/${rtcCall.slotId()}, member id ${rtcCall.memberId()}" +
                    ", ${kind.describe()}, intent $intent"
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

    override suspend fun callIntent(requested: MatrixRtcCallIntent?, applicationSlotId: String?): Result<MatrixRtcCallIntent> =
        runCatchingExceptions {
            val settled = withContext(ffiDispatcher) { rtcRoom.callIntent(callSlotId(applicationSlotId), requested?.toFfi()) }.toApi()
            Timber.i("MatrixRTC: $roomId's call intent is $settled, $requested requested")
            settled
        }.onFailure { Timber.w(it, "MatrixRTC: could not read $roomId's call intent") }

    override fun memberCountUpdates(applicationSlotId: String?): Flow<Int> = flow {
        val slotId = callSlotId(applicationSlotId)
        val subscription = withContext(ffiDispatcher) { rtcRoom.subscribeMembershipSnapshots(slotId) }
        subscription.use {
            while (true) {
                val snapshot = runCatchingExceptions { it.next() }
                    .onFailure { error -> Timber.w(error, "MatrixRTC: membership count for $roomId/$slotId failed") }
                    .getOrNull()
                    ?: break
                Timber.i("MatrixRTC: ${snapshot.size} member(s) in $roomId/$slotId before joining")
                emit(snapshot.size)
            }
        }
    }

    override suspend fun shutdown() {
        val joined = mutex.withLock {
            if (isShutdown) return
            isShutdown = true
            call.also { call = null }
        }
        // The core leaves whatever is still joined.
        withContext(ffiDispatcher) { rtcRoom.shutdown() }
        joined?.close()
        rtcRoom.close()
        Timber.i("MatrixRTC: $roomId shut down")
    }

    private suspend fun joinCallTyped(params: FfiJoinSessionParams) = try {
        rtcRoom.joinCall(params)
    } catch (e: MatrixRtcFfiException.JoinTimedOut) {
        throw MatrixRtcJoinException.TimedOut()
    } catch (e: MatrixRtcFfiException.JoinRefused) {
        throw MatrixRtcJoinException.Refused(errcode = e.errcode, reason = e.reason)
    } catch (e: MatrixRtcFfiException.NoLiveKitTransport) {
        throw MatrixRtcJoinException.NoLiveKitTransport(transportType = e.transportType)
    } catch (_: MatrixRtcFfiException.TokenEndpointUnsupported) {
        throw MatrixRtcJoinException.TokenEndpointUnsupported()
    } catch (_: MatrixRtcFfiException.SlotClosed) {
        throw MatrixRtcJoinException.SlotClosed()
    }

    private fun MatrixRtcCallKind.toFfi(): FfiCallKind = when (this) {
        is MatrixRtcCallKind.Group -> FfiCallKind.Group(notify?.toFfi())
        is MatrixRtcCallKind.Direct -> FfiCallKind.Direct(ring?.toFfi())
    }

    private fun MatrixRtcGroupNotify.toFfi() = FfiGroupNotify(
        // Lowercase because it goes out verbatim as `m.call.intent`.
        intent = intent?.toWire(),
        lifetimeMs = lifetimeMs,
        mentionUserIds = mentionUserIds.map { it.value },
        mentionRoom = mentionRoom,
    )

    private fun MatrixRtcRing.toFfi() = FfiRingConfig(lifetimeMs = lifetimeMs, intent = intent?.toWire())

    private fun MatrixRtcCallKind.describe(): String = when (this) {
        is MatrixRtcCallKind.Group -> "group, notify ${notify?.intent ?: "none"}"
        is MatrixRtcCallKind.Direct -> "direct, ring ${ring?.let { "${it.intent} for ${it.lifetimeMs ?: "default"} ms" } ?: "none"}"
    }

    private fun MatrixRtcCallIntent.toFfi(): FfiCallIntent = when (this) {
        MatrixRtcCallIntent.AUDIO -> FfiCallIntent.AUDIO
        MatrixRtcCallIntent.VIDEO -> FfiCallIntent.VIDEO
    }

    private fun FfiCallIntent.toApi(): MatrixRtcCallIntent = when (this) {
        FfiCallIntent.AUDIO -> MatrixRtcCallIntent.AUDIO
        FfiCallIntent.VIDEO -> MatrixRtcCallIntent.VIDEO
    }

    private fun MatrixRtcCallIntent.toWire(): String = when (this) {
        MatrixRtcCallIntent.AUDIO -> "audio"
        MatrixRtcCallIntent.VIDEO -> "video"
    }

    private companion object {
        /** How long the core waits before considering a silent member gone. */
        const val KEEP_ALIVE_TIMEOUT_MS = 20_000uL
    }
}
