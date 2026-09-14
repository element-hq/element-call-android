/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrixrtc.api

import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import kotlinx.coroutines.flow.StateFlow

/**
 * A MatrixRTC session we have joined: one `(roomId, slotId)` pair.
 *
 * Membership. Media is attached separately with [connectMedia], and what participants are actually
 * publishing belongs to the [MatrixRtcCall] that returns - but [members] is available from the
 * moment we join, before there is any media at all.
 */
interface MatrixRtcSession : AutoCloseable {
    val roomId: RoomId
    val slotId: String

    /**
     * Who the RTC core currently considers joined to this slot, ourselves included.
     *
     * This is the core's membership projection, not the media roster: it is built from the MSC4354
     * sticky events in the room, so it is populated before - and independently of - anything being
     * published. [MatrixRtcCall.participants] is the transport's view of the same call.
     *
     * Known to under-report on the current library: see [memberCount], and item 7 in
     * `libraries/rustrtc/FEEDBACK.md`. Prefer [memberCount] wherever a count is all that is needed.
     */
    val members: StateFlow<List<MatrixRtcMembership>>

    /**
     * How many memberships the core counts in this slot, ourselves included.
     *
     * Answers the same question as `members.size`, but the core delivers the two differently, and
     * that is why they can disagree. This one is a question we ask: the core counts the slot's
     * memberships when called and returns a number, so it is right whenever it is read. [members]
     * arrives instead on a subscription the core has to wake, and it does not wake it for the Element
     * Call compat entry point - so [members] can sit empty for an entire call whose membership this
     * tracks correctly. Only the subscription carries identities, so [members] remains the source for
     * *who* is in the call, for as long as it reports anyone at all.
     *
     * Zero before the first reading, which on a call we have joined is a passing state and not a
     * claim that the call is empty.
     */
    val memberCount: StateFlow<Int>

    /**
     * Connect to the session's media transport.
     *
     * @param transport where to connect. Must be one the session was joined with.
     */
    suspend fun connectMedia(transport: MatrixRtcTransport.LiveKit): Result<MatrixRtcCall>

    /**
     * Leave the session, publishing a leave membership event.
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
 * Why we are leaving a session. [code] is the machine-readable MSC4143 reason.
 */
data class MatrixRtcLeaveReason(
    val code: String,
    val reason: String? = null,
)
