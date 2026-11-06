/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc

import io.element.android.call.api.rtc.id.UserId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** One reaction we can send: an entry of the core's catalogue, the set and order every client's picker shows (feature-hq 009 R11). */
data class MatrixRtcReactionKind(
    /** What peers pick a sound by: the interoperable part. */
    val name: String,
    val emoji: String,
)

/**
 * A received reaction, at the moment it should appear: after its [spread], with its sound already started if it has one
 * (feature-hq 037 R2b).
 */
data class MatrixRtcReaction(
    /** In order of arrival, per call. */
    val id: Long,
    val memberId: String,
    val userId: UserId,
    /** What to draw: the catalogue's emoji for a known name, otherwise what was sent (037 R9). */
    val emoji: String,
    /** How long it waited before appearing, out of its [LIFETIME]. */
    val spread: Duration,
) {
    companion object {
        /** Fully visible this long and gone by [LIFETIME], both from arrival rather than appearance (037 R1a). */
        val VISIBLE_FOR: Duration = 3.seconds
        val LIFETIME: Duration = 4.seconds
    }
}
