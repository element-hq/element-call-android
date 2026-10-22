/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc

import io.element.android.call.api.rtc.id.RoomId
import kotlinx.coroutines.flow.Flow

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

    /** Whether the homeserver answers: the current value, then every change. Live from the client, before any call. */
    fun signallingHealth(): Flow<MatrixRtcSignallingHealth>

    /**
     * The host's Matrix client went offline (`false`), or a request has succeeded since it was (`true`): never that sync merely
     * started, which would retry every send for nothing (feature-hq 034 C3). Online retries every pending send at once.
     */
    fun reportConnectivity(isOnline: Boolean)
}
