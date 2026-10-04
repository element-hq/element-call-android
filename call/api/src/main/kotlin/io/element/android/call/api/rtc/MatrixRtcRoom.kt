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
     * @param applicationSlotId the MSC4143 application slot id; null is the room-wide call, `m.call#room`.
     * @param notify an MSC4075 notification to send with the join, which is what makes the other
     * devices in the room ring. Null joins quietly, which is what joining a call someone else started
     * does; see [MatrixRtcNotify].
     */
    suspend fun joinCall(applicationSlotId: String? = null, notify: MatrixRtcNotify? = null): Result<MatrixRtcCall>

    /** Leave any call still joined here, then stop following the room. */
    suspend fun shutdown()
}
