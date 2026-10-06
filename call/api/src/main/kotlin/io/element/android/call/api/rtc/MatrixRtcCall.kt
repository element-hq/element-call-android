/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc

import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.api.rtc.id.UserId
import kotlinx.coroutines.flow.StateFlow

/**
 * Our participation in a call, as the core's `RtcCall`: one `(roomId, slotId)` pair, joined with
 * [MatrixRtcRoom.joinCall].
 *
 * Membership. Media is attached separately with [connectMedia], and what participants are actually
 * publishing belongs to the [MatrixRtcMediaSession] that returns - but [members] is available from the
 * moment we join, before there is any media at all.
 */
interface MatrixRtcCall : AutoCloseable {
    val roomId: RoomId
    val slotId: String

    /** Our own MSC4143 member id, minted by the core for this join. */
    val memberId: String

    /**
     * Who the RTC core currently considers joined to this slot, ourselves included.
     *
     * The core's membership projection, not the media roster, so it is populated before - and
     * independently of - anything being published.
     */
    val members: StateFlow<List<MatrixRtcMembership>>

    /** How many memberships the core counts in this slot, ourselves included: the size of [members]. */
    val memberCount: StateFlow<Int>

    /** Connect to the media transport the call was joined on. */
    suspend fun connectMedia(): Result<MatrixRtcMediaSession>

    /**
     * Leave the call, publishing a leave membership event.
     */
    suspend fun leave(reason: MatrixRtcLeaveReason? = null): Result<Unit>
}

/**
 * One MSC4143 membership of an RTC session, as the core sees it.
 *
 * [memberId] is minted by the core, fresh for every join, and is what every sticky event, media
 * roster entry and frame-encryption report is keyed by. A device that rejoined without leaving
 * therefore appears under a new id; matching on [userId] and [deviceId] is the only way to
 * recognise it as the same device.
 */
data class MatrixRtcMembership(
    val memberId: String,
    val userId: UserId,
    /** Null when the membership was not encrypted, so no device could be attested. */
    val deviceId: String?,
    /** The MSC4143 application type, for instance `m.call`. Null when the membership did not say. */
    val application: String?,
)

/**
 * Why we are leaving a call. [code] is the machine-readable MSC4143 reason.
 */
data class MatrixRtcLeaveReason(
    val code: String,
    val reason: String? = null,
)
