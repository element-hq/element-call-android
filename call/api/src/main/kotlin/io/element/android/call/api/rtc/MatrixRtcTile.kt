/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc

import io.element.android.call.api.rtc.id.UserId

/**
 * What a tile is: a person, or a screen they are sharing.
 *
 * Not a [MatrixRtcStreamKind]. A person tile draws the member's camera and carries their microphone
 * state; a share tile draws the screen. Which stream a tile draws is [videoStreamKind], so nothing
 * guesses it - and a microphone can never be spelled as a tile.
 */
enum class MatrixRtcTileKind {
    PERSON,
    SCREEN_SHARE,
    ;

    /** The stream this kind of tile draws: what [MatrixRtcCall.videoFrames] and [MatrixRtcCall.setVideoConstraints] take. */
    val videoStreamKind: MatrixRtcStreamKind
        get() = when (this) {
            PERSON -> MatrixRtcStreamKind.CAMERA
            SCREEN_SHARE -> MatrixRtcStreamKind.SCREEN_SHARE
        }
}

/**
 * A tile's identity: one member, and whether this is them or their screen.
 *
 * Stable while the tile is in the call; a member's person tile keeps it when their screen share
 * starts and stops. The media plane is reached through [MatrixRtcTileKind.videoStreamKind].
 */
data class MatrixRtcTileId(
    val memberId: String,
    val kind: MatrixRtcTileKind,
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
    /** Speaking now, as the transport hears it. Not damped: only the order is, so a tile can be speaking and not move. */
    val isSpeaking: Boolean,
    /** Server-clock milliseconds, null when the hand is down. */
    val handRaisedAtMs: Long?,
    val isReachable: Boolean,
)

/**
 * One of a member's streams, of any kind: what per-stream state - frame flows, constraints, receive
 * statistics - is keyed by. Not a [MatrixRtcTileId], which names a renderable tile; this can name a
 * microphone.
 */
data class MatrixRtcStreamRef(
    val memberId: String,
    val kind: MatrixRtcStreamKind,
)

/**
 * A tile's place in the order: enough to place it, and to draw it as an avatar with a name when
 * its full record is outside the detail window.
 */
data class MatrixRtcTileRef(
    val id: MatrixRtcTileId,
    val userId: UserId,
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

/**
 * A member's person tile as the transport's roster describes them, unranked and never a hero.
 *
 * For the moment before the core publishes a tile of its own - our own in particular, which only
 * arrives once our membership reaches the core's roster - and for fixtures. Not speaking: the
 * roster does not carry it.
 */
fun MatrixRtcParticipant.personTile() = MatrixRtcTile(
    id = MatrixRtcTileId(memberId, MatrixRtcTileKind.PERSON),
    userId = userId,
    deviceId = deviceId,
    isHero = false,
    hasVideo = streams.any { it.kind == MatrixRtcStreamKind.CAMERA && !it.isMuted },
    isMicrophoneMuted = streams.none { it.kind == MatrixRtcStreamKind.MICROPHONE && !it.isMuted },
    isSpeaking = false,
    handRaisedAtMs = null,
    isReachable = isReachable,
)
