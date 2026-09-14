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
import io.element.android.call.api.rtc.MatrixRtcCallIntent
import io.element.android.call.api.rtc.MatrixRtcElementCallCompat
import io.element.android.call.api.rtc.MatrixRtcNotificationType
import io.element.android.call.api.rtc.MatrixRtcNotify
import io.element.android.call.api.rtc.MatrixRtcService
import io.element.android.call.api.rtc.MatrixRtcSession
import io.element.android.call.api.rtc.MatrixRtcTransport
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.impl.util.childScope
import io.element.android.call.impl.util.runCatchingExceptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import uniffi.matrix_rtc_ffi.FfiElementCallCompat
import uniffi.matrix_rtc_ffi.FfiJoinSessionParams
import uniffi.matrix_rtc_ffi.FfiNotificationType
import uniffi.matrix_rtc_ffi.FfiNotifyConfig
import uniffi.matrix_rtc_ffi.FfiTransportConfig
import uniffi.matrix_rtc_ffi.RtcSessionManagerHandle
import java.util.concurrent.ConcurrentHashMap

/**
 * The RTC core for one Matrix session, bridged to Matrix through an [ElementCallMatrixTransport].
 *
 * @param sessionCoroutineScope lives as long as the Matrix session: the core, its session-long feeds and
 * the rooms open for a call all hang off it.
 */
internal class RustMatrixRtcService(
    private val transport: ElementCallMatrixTransport,
    private val dispatchers: ElementCallDispatchers,
    /** Only reaches as far as the camera, via the session and then the call. */
    private val context: Context,
    private val sessionCoroutineScope: CoroutineScope,
) : MatrixRtcService {
    /**
     * FFI calls are *started* on a single thread. The core is internally synchronised and its
     * exported methods now suspend, so uniffi resumes them wherever it likes - what this buys is
     * that the order we hand things over in is the order they are picked up, and that no FFI call
     * is initiated from the main dispatcher.
     */
    private val ffiDispatcher = dispatchers.io.limitedParallelism(1)

    /**
     * Built by [start] and never rebuilt. One core for the whole Matrix session is the shape the library
     * is designed for: it is the side that is supposed to notice a call starting anywhere and tell us,
     * which a per-call handle could not do. It also means the core keeps whatever it has accumulated -
     * memberships, keys - across calls, so replacing it would silently discard all of it.
     *
     * That accumulation is a double edge: per-member key state surviving a leave is the current suspect
     * for the joiner sitting at MISSING_KEY on a second call in the same process.
     */
    private var managerOrNull: RtcSessionManagerHandle? = null
    private val startMutex = Mutex()

    private suspend fun manager(): RtcSessionManagerHandle {
        start()
        return checkNotNull(managerOrNull)
    }

    private val transportDiscovery = RtcTransportDiscovery(transport)

    /**
     * The rooms currently open for a call, by id. A room is opened per call while the command sender
     * lives as long as the session, so the sender looks the room a command is for up here.
     */
    private val openRooms = ConcurrentHashMap<RoomId, ElementCallMatrixRoom>()

    override suspend fun start() {
        // Mutually excluded rather than lazy because the side effects matter as much as the value: the
        // to-device subscription below must be established exactly once for the session, and two
        // callers racing here would either subscribe twice - feeding every key to the core twice -
        // or leave one of them holding a manager that is not the one calls are joined on.
        startMutex.withLock {
            if (managerOrNull != null) return
            val newManager = withContext(ffiDispatcher) {
                MatrixRtcFfi.ensureInitialized()
                RtcSessionManagerHandle().apply {
                    setCommandSender(
                        MatrixRtcCommandSender(
                            transport = transport,
                            commandDispatcher = dispatchers.io,
                            roomProvider = { roomId -> openRooms[roomId] },
                        )
                    )
                }
            }
            managerOrNull = newManager
            SessionStateFeeder(
                manager = newManager,
                transport = transport,
                // The session scope, not a call scope: the whole point is to be subscribed between
                // calls, so this must outlive every join and leave.
                scope = sessionCoroutineScope,
            ).start()
            Timber.i("MatrixRTC: core started for ${transport.userId}")
        }
    }

    override suspend fun discoverTransports(): Result<List<MatrixRtcTransport>> = transportDiscovery.discover()

    override suspend fun joinSession(
        roomId: RoomId,
        slotId: String,
        application: String,
        transport: MatrixRtcTransport?,
        elementCallCompat: MatrixRtcElementCallCompat,
        notify: MatrixRtcNotify?,
    ): Result<MatrixRtcSession> = runCatchingExceptions {
        // manager() starts the core if nothing has yet. Belt and braces: the session should already be
        // bridged from app launch, and starting here is too late to have caught keys sent before now.
        // It keeps a caller that forgot from getting a call with no core at all, and being idempotent
        // it costs nothing when start() already ran.
        val manager = manager()
        warnIfSlotIdIsMalformed(slotId, application)

        // The room is opened before anything is fed or joined: the join arms the delayed event through
        // it, and the membership feed reads from it. It is the last thing to go down too, after the
        // leave - see RustMatrixRtcSession.onSessionEnded.
        val room = this.transport.openRoom(roomId).getOrThrow()
        openRooms.put(roomId, room)?.let {
            Timber.w("MatrixRTC: replacing the open room for $roomId")
        }

        return@runCatchingExceptions runCatchingExceptions {
            joinWithRoom(manager, room, slotId, application, transport, elementCallCompat, notify)
        }.onFailure {
            openRooms.remove(roomId, room)
            room.close()
        }.getOrThrow()
    }

    private suspend fun joinWithRoom(
        manager: RtcSessionManagerHandle,
        room: ElementCallMatrixRoom,
        slotId: String,
        application: String,
        transport: MatrixRtcTransport?,
        elementCallCompat: MatrixRtcElementCallCompat,
        notify: MatrixRtcNotify?,
    ): MatrixRtcSession {
        val roomId = room.roomId

        // One scope per session: cancelling it stops every feed loop for that session at once.
        val scope = sessionCoroutineScope.childScope(dispatchers.io, "MatrixRtcSession-$roomId-$slotId")

        // The feeder writes it after every membership feed, the session exposes it. Held here because
        // it is the one thing the two halves of a join have to share: the feeder is what knows when
        // the membership changed, and the session is what the caller gets handed.
        val memberCount = MutableStateFlow(0)

        val feeder = RoomStateFeeder(
            manager = manager,
            room = room,
            ownUserId = this.transport.userId,
            scope = scope,
            // Passed in rather than read later, because it decides how a membership is parsed on the
            // way in. Feeding one dialect and joining in another is not an error but a silence: a
            // call that connects and in which nobody appears.
            elementCallCompat = elementCallCompat,
            slotId = slotId,
            memberCount = memberCount,
        )

        val memberId: String = withContext(ffiDispatcher) {
            // Room members and encryption first: without them the core excludes every membership
            // candidate as `SenderNotInRoom`. Memberships themselves come after the join, which is
            // what fixes the dialect they are read in - see RoomStateFeeder.start.
            feeder.start()

            // The core mints the member id and hands it back. It is the id every sticky event, media
            // roster entry and frame-encryption line is keyed by, so this one value ties our own logs
            // to the far end's - and it is what the media session reports itself under. Deriving one
            // locally is the thing MSC4143 forbids, so having the core own it end to end removes the
            // last place a host could get that wrong.
            manager.join(
                FfiJoinSessionParams(
                    userId = this@RustMatrixRtcService.transport.userId.value,
                    deviceId = this@RustMatrixRtcService.transport.deviceId.value,
                    roomId = roomId.value,
                    slotId = slotId,
                    application = application,
                    transport = transport.toFfi(),
                    canSubscribe = CAN_SUBSCRIBE,
                    keepAliveTimeoutMs = KEEP_ALIVE_TIMEOUT_MS,
                    // Both nulls defer to the core. For the sticky duration that also keeps a
                    // single side in charge of the membership lifetime - it hands us the duration
                    // to use with each sendStickyEvent, so there is nothing for us to choose here.
                    stickyDurationMs = null,
                    // Null means "follow whatever the slot prescribes" rather than choosing locally.
                    encryptionConfig = null,
                    elementCallCompat = elementCallCompat.toFfi(),
                    // Null joins quietly. The core suppresses the notification anyway once anyone
                    // else is in the session, so passing one for a call that turns out to be already
                    // running rings nobody.
                    notify = notify?.toFfi(),
                )
            )
        }
        Timber.d(
            "MatrixRTC: joined $roomId/$slotId as $application, member id $memberId, compat $elementCallCompat" +
                ", notify ${notify?.let { "${it.type} (${it.intent})" } ?: "none"}"
        )

        return RustMatrixRtcSession(
            roomId = roomId,
            slotId = slotId,
            localMemberId = memberId,
            manager = manager,
            transport = this.transport,
            sessionScope = scope,
            dispatchers = dispatchers,
            ffiDispatcher = ffiDispatcher,
            context = context,
            memberCount = memberCount,
            onSessionEnded = {
                // Only the room opened for this join: a later join in the same room has its own.
                openRooms.remove(roomId, room)
                // Not on the call scope, which is already cancelled by the time the session ends.
                sessionCoroutineScope.launch { room.close() }
            },
        ).also {
            // Subscribed before a single membership is fed, and `start` suspends until it is: the
            // subscription reports what changes after it exists, so anything fed in between is
            // invisible. Joining a call that is already running feeds the whole roster at once and
            // then goes quiet, so losing that first batch loses it for the rest of the call.
            it.start()
            feeder.startMemberships()
        }
    }

    /**
     * MSC4143 requires a slot id to start with `{applicationType}#`, and nothing we call enforces it.
     *
     * The core validates this in `openSlot`, which has no data source on Android so we never call it,
     * and `join` takes whatever it is given. A malformed slot id therefore produces a call that looks
     * entirely healthy from here - membership published, media connected, audio flowing - while a
     * conformant peer refuses the membership on sight and we never learn why. That is how `m.call`
     * survived until Element Call rejected it with "slot_id must start with m.call#".
     *
     * A warning rather than a failure: the library is the side that owns this rule, and refusing a
     * join it would accept means guessing at a policy that may not stay ours.
     */
    private fun warnIfSlotIdIsMalformed(slotId: String, application: String) {
        val required = "$application#"
        if (!slotId.startsWith(required)) {
            Timber.w(
                "MatrixRTC: slot id '$slotId' does not start with '$required', which MSC4143 requires - " +
                    "conformant peers will reject our membership while this side looks healthy"
            )
        }
    }

    private fun MatrixRtcElementCallCompat.toFfi(): FfiElementCallCompat = when (this) {
        MatrixRtcElementCallCompat.OFF -> FfiElementCallCompat.OFF
        MatrixRtcElementCallCompat.STICKY_EVENTS -> FfiElementCallCompat.STICKY_EVENTS
        MatrixRtcElementCallCompat.STATE_EVENTS -> FfiElementCallCompat.STATE_EVENTS
    }

    private fun MatrixRtcTransport?.toFfi(): FfiTransportConfig? = when (this) {
        null -> null
        is MatrixRtcTransport.LiveKit -> FfiTransportConfig(type = LIVEKIT, livekitServiceUrl = serviceUrl)
        // Advertise nothing rather than a transport we cannot actually publish on.
        is MatrixRtcTransport.Unsupported -> null
    }

    private fun MatrixRtcNotify.toFfi(): FfiNotifyConfig = FfiNotifyConfig(
        notificationType = when (type) {
            MatrixRtcNotificationType.RING -> FfiNotificationType.RING
            // The core spells the silent one NOTIFICATION where the receive side says NOTIFY; the two
            // enums mean the same thing, which is the whole reason this mapper exists.
            MatrixRtcNotificationType.NOTIFY -> FfiNotificationType.NOTIFICATION
        },
        // Lowercase because the wire value is, and it goes out verbatim as `m.call.intent`.
        intent = intent?.toWire(),
        // Null leaves the core's default in place rather than restating it here, so its cap and its
        // idea of how long a phone should ring stay in one place.
        lifetimeMs = lifetimeMs,
        mentionUserIds = mentionUserIds.map { it.value },
        mentionRoom = mentionRoom,
    )

    private fun MatrixRtcCallIntent.toWire(): String = when (this) {
        MatrixRtcCallIntent.AUDIO -> "audio"
        MatrixRtcCallIntent.VIDEO -> "video"
    }

    private companion object {
        const val LIVEKIT = "livekit"
        val CAN_SUBSCRIBE = listOf(LIVEKIT)

        /** How long the core waits before considering a silent member gone. */
        const val KEEP_ALIVE_TIMEOUT_MS = 20_000uL
    }
}
