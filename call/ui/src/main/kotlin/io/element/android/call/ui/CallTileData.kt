/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import io.element.android.call.api.ElementCallRoomMember
import io.element.android.call.api.rtc.MatrixRtcConnectionState
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcTile
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.MatrixRtcTileKind
import io.element.android.call.api.rtc.MatrixRtcTileRef
import io.element.android.call.api.rtc.MatrixRtcUnableToDecrypt
import io.element.android.call.api.rtc.id.UserId

/**
 * One of the core's tiles - a member's camera, or their screen - as it needs to be drawn.
 *
 * The join between two things that know nothing about each other: the core's [MatrixRtcTile], which
 * has member ids and stream states, and the room, which has names and avatars. Done once here so the
 * call screen and the minimized bar cannot disagree about who is in the call.
 */
data class CallTileData(
    /** The RTC member id. Fresh on every join, and what every per-member report is keyed by. */
    val memberId: String,
    val userId: UserId,
    /** Null until the room's member list has loaded, which is normal early in a call. */
    val roomMember: ElementCallRoomMember?,
    val isLocal: Boolean,
    /** Whether the microphone stream is muted, or absent entirely - both mean "cannot be heard". */
    val isMuted: Boolean,
    val isActiveSpeaker: Boolean,
    val hasVideo: Boolean,
    /** Only ever our own front camera: see [isVideoMirrored]'s use in the tile. */
    val isVideoMirrored: Boolean,
    /** Whether this tile's media is arriving and can be shown (034 R23, R29, R33). */
    val mediaState: CallTileMediaState = CallTileMediaState.LIVE,
    /**
     * Which of the member's streams this tile draws: with [memberId], the tile's identity.
     *
     * A member sharing their screen gets two tiles - themselves, and their screen - so [memberId]
     * alone is not enough to tell two of them apart. See [tileId].
     */
    val streamKind: MatrixRtcStreamKind = MatrixRtcStreamKind.CAMERA,
    /** Drawn only ever in the spotlight, never in the grid (spec 003 R17). On the reference, so known without detail. */
    val isHero: Boolean = false,
    /**
     * Whether the core has sent this tile's full record. False for a tile outside the detail window,
     * which is drawn from its reference: a name and an avatar, no badge, no ring, no video (R54).
     */
    val hasDetail: Boolean = true,
    /** The hand's place in the call's queue, 1 for the earliest; null while it is down, on a share, and wherever hands are not shown (009 R33). */
    val handPosition: Int? = null,
    /** Set on the callee a DM call is ringing: not a tile, but drawn in the slot their tile will take (030 R15, R21). */
    val ring: CallRing? = null,
) {
    val isHandRaised: Boolean get() = handPosition != null

    /**
     * The name tag shows the media indicator, and a remote tile draws its avatar rather than its last frame. Our own tile keeps
     * its live preview, which is local capture (034 R34).
     */
    val isMediaInterrupted: Boolean get() = mediaState != CallTileMediaState.LIVE

    /**
     * [MatrixRtcTile.id] as one string, for a keyed list and a test tag.
     *
     * The member id alone would collide between someone and their screen, and a collision in a keyed
     * layout is not a cosmetic problem: two tiles sharing a key means Compose reuses one composable
     * for both, and on the video path it means two collectors on what it thinks is one stream.
     *
     * Plain [memberId] for the ordinary case so that nothing changes for a call with no screen share
     * in it - including the ids in a test.
     */
    val tileId: String
        get() = if (streamKind == MatrixRtcStreamKind.CAMERA) memberId else "$memberId#$streamKind"

    /**
     * Falls back to the user id, which is always available and is at least identifying, rather than
     * to a placeholder like "Unknown" that would be identical for everyone whose profile is still
     * loading.
     */
    val displayName: String get() = roomMember?.displayName?.takeIf { it.isNotBlank() } ?: userId.value

    val isScreenShare: Boolean get() = streamKind == MatrixRtcStreamKind.SCREEN_SHARE

    /** What the core calls this tile: the identity the call layer is addressed by. */
    val id: MatrixRtcTileId
        get() = MatrixRtcTileId(memberId, if (isScreenShare) MatrixRtcTileKind.SCREEN_SHARE else MatrixRtcTileKind.PERSON)
}

/**
 * Build the tile model for one of the core's tiles.
 *
 * A member with no microphone stream at all reads as muted *on the badge*, deliberately: to anyone
 * looking at the call the two are the same fact, and drawing an un-muted icon for someone who cannot
 * be heard is the more misleading of the two options. The call layer logs the difference once, when
 * opening their audio finds nothing to open.
 *
 * A share tile carries its owner's microphone, but a screen is nobody's voice: the mute badge and the
 * speaking ring stay on the person's tile rather than being repeated on this one.
 */
fun MatrixRtcTile.toCallTileData(
    roomMembers: Map<UserId, ElementCallRoomMember>,
    isLocal: Boolean,
    isFrontCamera: Boolean,
    isMissingKeys: Boolean = false,
): CallTileData {
    val isScreenShare = id.kind == MatrixRtcTileKind.SCREEN_SHARE
    return CallTileData(
        memberId = id.memberId,
        userId = userId,
        roomMember = roomMembers[userId],
        isLocal = isLocal,
        isMuted = !isScreenShare && isMicrophoneMuted,
        isActiveSpeaker = !isScreenShare && isSpeaking,
        hasVideo = hasVideo,
        // Mirroring a remote member would be wrong twice over: it is not how they look to
        // themselves, and any text in frame reads backwards.
        isVideoMirrored = isLocal && !isScreenShare && isFrontCamera,
        mediaState = CallTileMediaState.of(connection, unableToDecrypt, isMissingKeys),
        streamKind = id.kind.videoStreamKind,
        isHero = isHero,
        hasDetail = true,
        handPosition = handRaisedPosition.takeIf { !isScreenShare && handRaisedAtMs != null },
    )
}

/**
 * Build the tile model for a remote tile from its place in the order, with its full record when
 * the core has sent one and from the reference alone when it has not: the tile is placed and named
 * either way, and never blank (R54).
 */
fun MatrixRtcTileRef.toCallTileData(
    detail: MatrixRtcTile?,
    roomMembers: Map<UserId, ElementCallRoomMember>,
    isFrontCamera: Boolean,
    isMissingKeys: Boolean = false,
): CallTileData {
    if (detail != null) {
        return detail.toCallTileData(roomMembers, isLocal = false, isFrontCamera = isFrontCamera, isMissingKeys = isMissingKeys).copy(isHero = isHero)
    }
    return CallTileData(
        memberId = id.memberId,
        userId = userId,
        roomMember = roomMembers[userId],
        isLocal = false,
        isMuted = false,
        isActiveSpeaker = false,
        hasVideo = false,
        isVideoMirrored = false,
        streamKind = id.kind.videoStreamKind,
        isHero = isHero,
        hasDetail = false,
    )
}

/** Where a DM call's ring is, for the callee's ringing avatar (030). */
enum class CallRing {
    /** Being sent, or ringing. */
    CALLING,

    /** It could not be sent, so they were never called (R4). */
    NOT_SENT,
}

/** Why a tile's media is not reaching the screen, if it is not. */
enum class CallTileMediaState {
    LIVE,

    /** Our own tile only, until our media server is first up. */
    CONNECTING,

    /** The connection carrying it is being re-established, or nothing can reach it. On our own tile, our own media server (034 R23). */
    RECONNECTING,

    /** It arrives, and cannot be decrypted (034 R33). */
    UNDECRYPTABLE,

    /** It has not been decryptable for long enough that the wait is not the moment around a key rotation (034 Q8). */
    KEYS_MISSING,

    /** Its sender declared it unencrypted in an encrypted call, and the core refused it. */
    NOT_ENCRYPTED,
    ;

    companion object {
        /** By its two causes. A lost connection wins: it is why nothing decrypts either. */
        fun of(connection: MatrixRtcConnectionState, unableToDecrypt: MatrixRtcUnableToDecrypt?, isMissingKeys: Boolean): CallTileMediaState =
            when (connection) {
                MatrixRtcConnectionState.CONNECTING -> CONNECTING
                MatrixRtcConnectionState.RECONNECTING,
                MatrixRtcConnectionState.UNREACHABLE -> RECONNECTING
                MatrixRtcConnectionState.CONNECTED -> when (unableToDecrypt) {
                    null -> LIVE
                    // Nothing to wait for: the core will never play it.
                    MatrixRtcUnableToDecrypt.NOT_ENCRYPTED -> NOT_ENCRYPTED
                    MatrixRtcUnableToDecrypt.MISSING_KEY,
                    MatrixRtcUnableToDecrypt.BAD_KEY -> if (isMissingKeys) KEYS_MISSING else UNDECRYPTABLE
                }
            }
    }
}
