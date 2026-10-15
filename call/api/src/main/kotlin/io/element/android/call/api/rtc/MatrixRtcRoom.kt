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
     */
    suspend fun joinCall(kind: MatrixRtcCallKind, applicationSlotId: String? = null): Result<MatrixRtcCall>

    /** Leave any call still joined here, then stop following the room. */
    suspend fun shutdown()
}
