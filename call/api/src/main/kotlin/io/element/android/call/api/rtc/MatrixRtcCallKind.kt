/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc

import io.element.android.call.api.rtc.id.UserId

/**
 * What kind of call a join is, which decides whether it can ring (feature-hq 030 C1).
 *
 * Whether a room is a DM is the host's mark, never a member count, and whether the user chose to call
 * is the host's to say: the core can infer neither.
 */
sealed interface MatrixRtcCallKind {
    /** Not a DM. Never rings; [notify] tells the room with a visual notification when we start the call. */
    data class Group(val notify: MatrixRtcGroupNotify?) : MatrixRtcCallKind

    /**
     * A DM call: the core rings, follows the call and ends it, see [MatrixRtcCall.status]. With [ring], the
     * user chose to call; without one, they are answering a ring or joining a call in progress.
     */
    data class Direct(val ring: MatrixRtcRing?) : MatrixRtcCallKind
}

/**
 * A group call's MSC4075 notification, sent with a join that starts the call. Shows an incoming call on
 * the other devices in the room; never rings.
 */
data class MatrixRtcGroupNotify(
    /** MSC4196 `m.call.intent`, which tells the others what they are being invited to. */
    val intent: MatrixRtcCallIntent?,
    /** How long the notification stays valid. Null defers to the core. */
    val lifetimeMs: ULong? = null,
    /** Whether the whole room is being summoned, which is what a call in a room means. */
    val mentionRoom: Boolean = true,
    /** Users to name individually in `m.mentions`. Usually empty. */
    val mentionUserIds: List<UserId> = emptyList(),
)

/** The ring a DM call sends when the user chose to call. It always addresses the whole room. */
data class MatrixRtcRing(
    /** MSC4196 `m.call.intent`. */
    val intent: MatrixRtcCallIntent?,
    /** How long the callee's phone rings, and how long the call waits for an answer. Null is the core's 90 s. */
    val lifetimeMs: ULong? = null,
)

/** MSC4196 `m.call.intent`: the wire value is the lowercase name. */
enum class MatrixRtcCallIntent {
    AUDIO,
    VIDEO,
}
