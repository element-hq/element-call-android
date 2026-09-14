/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

// Temporary: widget-driver stopgap, see `libraries/rustrtc/FEEDBACK.md`, "Widget-driver stopgap".

package io.element.android.call.matrix.temporary.widget

import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.api.matrix.ElementCallMatrixRoom
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * The bridges currently live, one per room in a call.
 *
 * Exists because a widget driver is per room while the command sender is per session: the sender looks
 * the room's bridge up here. An SDK-backed bridge would be session-wide and need no registry.
 */
internal class MatrixRtcBridgeRegistry {
    private val bridges = ConcurrentHashMap<RoomId, ElementCallMatrixRoom>()

    fun register(bridge: ElementCallMatrixRoom) {
        bridges.put(bridge.roomId, bridge)?.let {
            Timber.w("MatrixRTC: replacing the live bridge for ${bridge.roomId}")
        }
    }

    fun unregister(roomId: RoomId): ElementCallMatrixRoom? = bridges.remove(roomId)

    operator fun get(roomId: RoomId): ElementCallMatrixRoom? = bridges[roomId]

    /**
     * Any live bridge, for the operations the core does not scope to a room: a to-device message can
     * go through whichever driver is running. Warns when there is a choice, since a message sent through
     * a room's driver is encrypted according to that room.
     */
    fun any(): ElementCallMatrixRoom? {
        val live = bridges.values.toList()
        if (live.size > 1) {
            Timber.w("MatrixRTC: ${live.size} bridges live, using the one for ${live.first().roomId}")
        }
        return live.firstOrNull()
    }
}
