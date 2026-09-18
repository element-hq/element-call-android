/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api

import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.api.rtc.id.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * What the call needs to know about a room beyond what the RTC core sees: what to call the call, and
 * who its participants are.
 *
 * The RTC layer knows participants only as member ids and user ids. Names and avatars come from the
 * room, and a host that caches its members reads them from its cache here; the turnkey provider in
 * `element-call-matrix` reads them from the SDK without one.
 */
interface ElementCallRoomContextProvider {
    /**
     * The room's name, direct flag and members, emitted once known and again on every change. A
     * provider with nothing to say emits nothing rather than guessing.
     */
    fun roomContext(roomId: RoomId): Flow<ElementCallRoomContext>
}

data class ElementCallRoomContext(
    /** The room's display name, or null when it has none the host can compute. */
    val displayName: String?,
    /** Whether the room is a direct message, which decides between the one-to-one and the group layout, and whether the far end rings. */
    val isDm: Boolean,
    /** The room's members by user id, for putting names and faces to participants. */
    val members: Map<UserId, ElementCallRoomMember>,
)

data class ElementCallRoomMember(
    val userId: UserId,
    /** The member's display name in this room, or null when they have none. */
    val displayName: String?,
    /** The member's avatar as an `mxc://` URL, or null when they have none. */
    val avatarUrl: String?,
)

/** The default: no room knowledge. Tiles show user ids and the call is treated as a group call. */
object NoOpElementCallRoomContextProvider : ElementCallRoomContextProvider {
    override fun roomContext(roomId: RoomId): Flow<ElementCallRoomContext> = emptyFlow()
}
