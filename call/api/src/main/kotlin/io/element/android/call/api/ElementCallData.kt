/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api

import io.element.android.call.api.rtc.MatrixRtcCallIntent
import io.element.android.call.api.rtc.id.RoomId

/**
 * What a host asks for when it starts a call: which room, what the user chose, and whether the room is a DM.
 *
 * No session id: a stack is built per Matrix session, so the session is the one the stack belongs to.
 */
data class ElementCallData(
    val roomId: RoomId,
    /**
     * Voice or video when the user chose one; null when they join without choosing, and the call's own then applies
     * (feature-hq 045 R17, R18). Never video as a default, nor the last button pressed.
     */
    val intent: MatrixRtcCallIntent?,
    /**
     * True when the user chose to call, false when they answer a ring or join a call in progress. In a DM it
     * decides whether the other person's phone rings, so it must come from what the user did, never from
     * whether the room has a call: a call that ended a moment ago would turn an answer into a call back.
     * In a group room the lobby's button decides instead (045 R21).
     */
    val isStartingCall: Boolean,
    /** Whether the room is a DM, from the host's own room. Decides the lobby (045 R3, R40) and the ring (030). */
    val isDm: Boolean,
    /** Go straight in: the host has run its own pre-call screen (045 R5). Ignored in a DM, which never shows the lobby. */
    val skipsLobby: Boolean = false,
) {
    /** Whether the call opens on the lobby (045 R1-R3, R5). */
    val showsLobby: Boolean get() = !isDm && !skipsLobby
}
