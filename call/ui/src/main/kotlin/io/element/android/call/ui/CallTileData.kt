/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import io.element.android.call.api.ElementCallRoomMember
import io.element.android.call.api.rtc.MatrixRtcParticipant
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcTile
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
    /**
     * Whether the member publishes a microphone stream at all, muted or not.
     *
     * [isMuted] deliberately collapses this into itself for the badge, and that is still the right
     * call there - see `toCallTileData`. It is kept apart here because the two have different
     * *causes*: muted is a choice the member made, absent is us having nothing to play, and a member
     * who is talking away on another client while we draw a mute badge is the second one. That
     * happened - see the `Audio` section of the RTC `FEEDBACK.md` - and took a side-by-side with
     * Element Call to notice, because nothing anywhere said the stream was missing rather than off.
     */
    val hasMicrophone: Boolean = true,
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
}

/**
 * Build the tile model for one of the core's tiles.
 *
 * A member with no microphone stream at all reads as muted *on the badge*, deliberately: to anyone
 * looking at the call the two are the same fact, and drawing an un-muted icon for someone who cannot
 * be heard is the more misleading of the two options. The tile collapses them as well, so the
 * distinction comes from the transport's roster, [hasMicrophone], and is shown wherever the question
 * being asked is "why".
 *
 * A share tile carries its owner's microphone, but a screen is nobody's voice: the mute badge and the
 * speaking ring stay on the person's tile rather than being repeated on this one.
 */
fun MatrixRtcTile.toCallTileData(
    roomMembers: Map<UserId, ElementCallRoomMember>,
    isLocal: Boolean,
    hasMicrophone: Boolean,
    isFrontCamera: Boolean,
): CallTileData {
    val isScreenShare = id.kind == MatrixRtcStreamKind.SCREEN_SHARE
    return CallTileData(
        memberId = id.memberId,
        userId = userId,
        roomMember = roomMembers[userId],
        isLocal = isLocal,
        isMuted = !isScreenShare && isMicrophoneMuted,
        hasMicrophone = isScreenShare || hasMicrophone,
        isActiveSpeaker = !isScreenShare && isSpeaking,
        hasVideo = hasVideo,
        // Mirroring a remote member would be wrong twice over: it is not how they look to
        // themselves, and any text in frame reads backwards.
        isVideoMirrored = isLocal && !isScreenShare && isFrontCamera,
        isReachable = isReachable,
        streamKind = id.kind,
    )
}

/** Whether the participant publishes a stream of this kind at all, muted or not. */
fun MatrixRtcParticipant.hasStream(kind: MatrixRtcStreamKind) = streams.any { it.kind == kind }
