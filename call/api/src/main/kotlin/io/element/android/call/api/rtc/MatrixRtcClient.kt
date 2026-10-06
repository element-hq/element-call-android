/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc

import io.element.android.call.api.rtc.id.RoomId

/**
 * Entry point to MatrixRTC for one Matrix session. Mirrors the core's `RtcClient`: it holds nothing
 * until a room is asked for.
 */
interface MatrixRtcClient {
    /**
     * Open [roomId] for MatrixRTC: subscribe to what the core reads there, in [format].
     *
     * The room stays open until [MatrixRtcRoom.shutdown].
     */
    suspend fun room(roomId: RoomId, format: MatrixRtcMembershipFormat): Result<MatrixRtcRoom>
}
