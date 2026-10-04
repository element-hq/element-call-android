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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.matrix.rtc.RtcSessionManagerHandle
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * The RTC core for one Matrix session, bridged to Matrix through an [ElementCallMatrixTransport].
 *
 * [sessionCoroutineScope] lives as long as the Matrix session: the core, its session-long feeds and
 * the rooms open for a call all hang off it.
 */
internal class RustMatrixRtcClient(
    private val transport: ElementCallMatrixTransport,
    private val dispatchers: ElementCallDispatchers,
    /** Only reaches as far as the camera, via the room, the call and then the media session. */
    private val context: Context,
    private val sessionCoroutineScope: CoroutineScope,
) : MatrixRtcClient {
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
     * The rooms currently open, by id. A room is opened per call while the command sender lives as
     * long as the session, so the sender looks the room a command is for up here.
     */
    private val openRooms = ConcurrentHashMap<RoomId, ElementCallMatrixRoom>()

    /** Bring the core up with the session, so its to-device subscription exists between calls. Idempotent. */
    suspend fun start() {
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

    override suspend fun room(roomId: RoomId, format: MatrixRtcMembershipFormat): Result<MatrixRtcRoom> = runCatchingExceptions {
        // manager() starts the core if nothing has yet. Belt and braces: the session should already be
        // bridged from app launch, and starting here is too late to have caught keys sent before now.
        val manager = manager()

        // Opened before anything is fed or joined: the join arms the delayed event through it, and the
        // membership feed reads from it. It is the last thing to go down too, after the leave.
        val room = transport.openRoom(roomId).getOrThrow()
        openRooms.put(roomId, room)?.let {
            Timber.w("MatrixRTC: replacing the open room for $roomId")
        }
        RustMatrixRtcRoom(
            matrixRoom = room,
            format = format,
            manager = manager,
            transport = transport,
            transportDiscovery = transportDiscovery,
            dispatchers = dispatchers,
            ffiDispatcher = ffiDispatcher,
            context = context,
            sessionCoroutineScope = sessionCoroutineScope,
            onShutdown = {
                // Only this room: a later one for the same id has its own.
                openRooms.remove(roomId, room)
                room.close()
            },
        )
    }
}
