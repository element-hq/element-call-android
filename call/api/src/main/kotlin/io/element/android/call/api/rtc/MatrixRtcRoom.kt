/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc

import io.element.android.call.api.rtc.id.RoomId

/**
 * One room open for MatrixRTC, as the core's `RtcRoom`.
 */
interface MatrixRtcRoom {
    val roomId: RoomId

    /**
     * Join the call in this room.
     *
     * @param kind a group call or a DM call, with what it sends. Required: a default of a quiet group call
     * would let a caller that forgot to say DM place a call nobody notices.
     * @param applicationSlotId the MSC4143 application slot id; null is the room-wide call, `m.call#room`.
     * @return a [MatrixRtcJoinException] when the core gave the join up.
     */
    suspend fun joinCall(kind: MatrixRtcCallKind, applicationSlotId: String? = null): Result<MatrixRtcCall>

    /** Leave any call still joined here, then stop following the room. */
    suspend fun shutdown()
}

/** A join the core gave up on, typed so the screen can say why (feature-hq 034 C10). */
sealed class MatrixRtcJoinException(message: String) : Exception(message) {
    /** The join did not complete within its 30 s budget: the homeserver kept failing, or did not answer. */
    class TimedOut : MatrixRtcJoinException("the join timed out")

    /** The homeserver refused the join for good; [errcode] is its Matrix error code when it gave one. */
    class Refused(val errcode: String?, reason: String) : MatrixRtcJoinException("the join was refused: ${errcode ?: "no code"} $reason")
}
