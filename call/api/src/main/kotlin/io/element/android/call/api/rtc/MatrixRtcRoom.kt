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
 * One room open for MatrixRTC, as the core's `RtcRoom`.
 */
interface MatrixRtcRoom {
    val roomId: RoomId

    /**
     * Join the call in this room.
     *
     * @param kind a group call or a DM call, with what it sends. Required: a default of a quiet group call
     * would let a caller that forgot to say DM place a call nobody notices.
     * @param intent the intent [callIntent] settled, which our membership states and the core does not resolve again
     * (feature-hq 045 C6). The call's type, not the camera's state: a video call joined with the camera off is video.
     * @param applicationSlotId the MSC4143 application slot id; null is the room-wide call, `m.call#room`.
     * @return a [MatrixRtcJoinException] when the core gave the join up.
     */
    suspend fun joinCall(kind: MatrixRtcCallKind, intent: MatrixRtcCallIntent, applicationSlotId: String? = null): Result<MatrixRtcCall>

    /**
     * The call's intent without joining it: [requested] when given, else the one the members other than this device
     * agree on, else video (feature-hq 045 R17). The core's order; never re-implemented here.
     */
    suspend fun callIntent(requested: MatrixRtcCallIntent?, applicationSlotId: String? = null): Result<MatrixRtcCallIntent>

    /**
     * How many memberships the core counts in the call's slot, without joining it: the current value, then every change
     * (feature-hq 045 R42, R43). The same list the joined call counts. Ends if the core's subscription fails.
     */
    fun memberCountUpdates(applicationSlotId: String? = null): Flow<Int>

    /** Leave any call still joined here, then stop following the room. */
    suspend fun shutdown()
}

/** A join the core gave up on, typed so the screen can say why (feature-hq 034 C10). */
sealed class MatrixRtcJoinException(message: String) : Exception(message) {
    /** The join did not complete within its 30 s budget: the homeserver kept failing, or did not answer. */
    class TimedOut : MatrixRtcJoinException("the join timed out")

    /** The homeserver refused the join for good; [errcode] is its Matrix error code when it gave one. */
    class Refused(val errcode: String?, reason: String) : MatrixRtcJoinException("the join was refused: ${errcode ?: "no code"} $reason")

    /** The homeserver advertises no LiveKit transport the join's membership format can use. */
    class NoLiveKitTransport(val transportType: String) : MatrixRtcJoinException("no LiveKit transport of type $transportType")

    /** The homeserver cannot hand out a media-server token the way the join's membership format asks for one (038 R44). */
    class TokenEndpointUnsupported : MatrixRtcJoinException("the homeserver has no MSC4195 token endpoint")

    /** The call's slot was not open, so our membership was not sent. */
    class SlotClosed : MatrixRtcJoinException("the call's slot is closed")
}
