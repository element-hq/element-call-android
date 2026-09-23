/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc

import io.element.android.call.api.rtc.id.UserId

/**
 * A tile's identity: one video stream of one membership.
 *
 * The same pair the media plane is addressed by, so [MatrixRtcCall.videoFrames] and
 * [MatrixRtcCall.setVideoConstraints] take its two halves. Stable while the tile is in the call; a
 * member's camera tile keeps it when their screen share starts and stops.
 */
data class MatrixRtcTileId(
    val memberId: String,
    /** [MatrixRtcStreamKind.CAMERA] or [MatrixRtcStreamKind.SCREEN_SHARE]. */
    val kind: MatrixRtcStreamKind,
)

/**
 * One renderable stream, as the core ranks it. A member sharing their screen is two tiles.
 */
data class MatrixRtcTile(
    val id: MatrixRtcTileId,
    val userId: UserId,
    val deviceId: String?,
    /** Sorts above every other tile and is not displaced by ranking; a screen share, today. */
    val isHero: Boolean,
    /** This tile's own stream is present and unmuted. On a share tile it is the share. */
    val hasVideo: Boolean,
    /** The member's microphone is absent or muted. The same on every tile the member owns. */
    val isMicrophoneMuted: Boolean,
    /** Damped by the core, so it is what the order was ranked on rather than the raw meter. */
    val isSpeaking: Boolean,
    /** Server-clock milliseconds, null when the hand is down. */
    val handRaisedAtMs: Long?,
    val isReachable: Boolean,
)

data class MatrixRtcTileRef(
    val id: MatrixRtcTileId,
    val isHero: Boolean,
)

/**
 * Every remote tile in the call, in the core's rank order, and the full records it sent for them.
 *
 * Render [order] as given: the core already ranked and damped it, and a re-sort fights that. Our own
 * tile is never in it, see [MatrixRtcLocalState].
 */
data class MatrixRtcTileRoster(
    val order: List<MatrixRtcTileRef>,
    /** Keyed by identity: the core sends a subsequence of [order], so joining by index is wrong. */
    val detail: Map<MatrixRtcTileId, MatrixRtcTile>,
) {
    /** The tiles of [order] that have a full record, still in rank order. */
    val ranked: List<MatrixRtcTile>
        get() = order.mapNotNull { detail[it.id] }

    companion object {
        val EMPTY = MatrixRtcTileRoster(order = emptyList(), detail = emptyMap())
    }
}

/**
 * Facts about us, which change when the local user acts rather than when the call moves.
 */
data class MatrixRtcLocalState(
    /** Our camera tile. Never ranked, never a hero. */
    val tile: MatrixRtcTile,
    /**
     * Whether a screen-share publication of ours is up and unmuted.
     *
     * Derived from the publication, not from what we asked for, so it goes false however the share ended.
     */
    val isScreenSharing: Boolean,
)
