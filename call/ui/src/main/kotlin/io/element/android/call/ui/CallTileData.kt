/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import io.element.android.call.api.ElementCallRoomMember
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcTile
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.MatrixRtcTileKind
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
    /**
     * Whether the transport can currently reach this member.
     *
     * The nearest thing the FFI gives us to "which SFU is this person on". Matrix RTC allows a call to
     * be carried by several SFUs at once, so in a federated deployment a member can be in the call and
     * not reachable from ours - but `FfiParticipant` exposes only this boolean, not the transport, so
     * this is the whole of what a debug readout can say about it. Shown in the tile stats overlay only.
     */
    val isReachable: Boolean = true,
    /**
     * Which of the member's streams this tile draws: with [memberId], the tile's identity.
     *
     * A member sharing their screen gets two tiles - themselves, and their screen - so [memberId]
     * alone is not enough to tell two of them apart. See [tileId].
     */
    val streamKind: MatrixRtcStreamKind = MatrixRtcStreamKind.CAMERA,
) {
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
        isReachable = isReachable,
        streamKind = id.kind.videoStreamKind,
    )
}
