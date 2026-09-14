/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.callnative.impl.ui

import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.RoomMember
import io.element.android.libraries.matrixrtc.api.MatrixRtcParticipant
import io.element.android.libraries.matrixrtc.api.MatrixRtcStreamKind

/**
 * One member of the call, as a tile needs to draw them.
 *
 * The join between two things that know nothing about each other: the RTC layer, which has member
 * ids and stream states, and the room, which has names and avatars. Done once here so the call
 * screen and the minimized bar cannot disagree about who is in the call.
 */
data class CallParticipant(
    /** The RTC member id. Fresh on every join, and what every per-member report is keyed by. */
    val memberId: String,
    val userId: UserId,
    /** Null until the room's member list has loaded, which is normal early in a call. */
    val roomMember: RoomMember?,
    val isLocal: Boolean,
    /** Whether the microphone stream is muted, or absent entirely - both mean "cannot be heard". */
    val isMuted: Boolean,
    /**
     * Whether the member publishes a microphone stream at all, muted or not.
     *
     * [isMuted] deliberately collapses this into itself for the badge, and that is still the right
     * call there - see [toCallParticipant]. It is kept apart here because the two have different
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
     * Which of the member's streams this tile draws.
     *
     * A member sharing their screen gets two tiles - themselves, and their screen - so a tile is no
     * longer one per member and [memberId] is no longer enough to tell two of them apart. See
     * [tileId].
     */
    val streamKind: MatrixRtcStreamKind = MatrixRtcStreamKind.CAMERA,
) {
    /**
     * What identifies this tile in a keyed list, as [memberId] used to.
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
 * Build the tile model for one RTC participant.
 *
 * A member with no microphone stream at all reads as muted *on the badge*, deliberately: to anyone
 * looking at the call the two are the same fact, and drawing an un-muted icon for someone who cannot
 * be heard is the more misleading of the two options.
 *
 * The distinction is still carried, on [CallParticipant.hasMicrophone], and shown wherever the
 * question being asked is "why". Collapsing it everywhere was the actual mistake: the badge choice
 * was sound, but it left no surface at all on which a missing stream looked different from a mute,
 * so a member the SFU was happily relaying to everyone else read here as having muted themselves.
 */
fun MatrixRtcParticipant.toCallParticipant(
    roomMembers: Map<UserId, RoomMember>,
    activeSpeakerIds: Set<String>,
    isFrontCamera: Boolean,
): CallParticipant {
    val microphone = streams.firstOrNull { it.kind == MatrixRtcStreamKind.MICROPHONE }
    val hasVideo = streams.any { it.kind == MatrixRtcStreamKind.CAMERA && !it.isMuted }
    return CallParticipant(
        memberId = memberId,
        userId = userId,
        roomMember = roomMembers[userId],
        isLocal = isLocal,
        isMuted = microphone == null || microphone.isMuted,
        hasMicrophone = microphone != null,
        isActiveSpeaker = memberId in activeSpeakerIds,
        hasVideo = hasVideo,
        // Mirroring a remote member would be wrong twice over: it is not how they look to
        // themselves, and any text in frame reads backwards.
        isVideoMirrored = isLocal && isFrontCamera,
        isReachable = isReachable,
    )
}

/** Whether the participant has a stream of this kind that is actually sending. */
fun MatrixRtcParticipant.publishes(kind: MatrixRtcStreamKind) = streams.any { it.kind == kind && !it.isMuted }

/**
 * The tile id a member's screen share gets, kept next to [CallParticipant.tileId] so the presenter
 * cannot key the frame map differently from the way the tile keys itself.
 */
fun screenShareTileId(memberId: String) = "$memberId#${MatrixRtcStreamKind.SCREEN_SHARE}"

/**
 * The tiles one RTC participant is drawn as: themselves, and their screen if they are sharing one.
 *
 * Only *their* screen. Our own share is published but never drawn, because the person sharing a
 * screen is already looking at it - and because we do not subscribe to our own outgoing stream, so
 * there would be no frames to draw even if we wanted the tile.
 */
fun MatrixRtcParticipant.toCallTiles(
    roomMembers: Map<UserId, RoomMember>,
    activeSpeakerIds: Set<String>,
    isFrontCamera: Boolean,
): List<CallParticipant> {
    val person = toCallParticipant(roomMembers, activeSpeakerIds, isFrontCamera)
    if (isLocal || !publishes(MatrixRtcStreamKind.SCREEN_SHARE)) return listOf(person)
    return listOf(
        person,
        person.copy(
            streamKind = MatrixRtcStreamKind.SCREEN_SHARE,
            hasVideo = true,
            isVideoMirrored = false,
            // A screen has no microphone and is nobody's voice, so the mute badge and the speaking
            // ring belong on the person's tile rather than being repeated on this one. Same for the
            // missing-stream notice: a screen is not expected to have one, so saying it has none
            // would report a fault on the one tile where it is the normal state.
            isMuted = false,
            hasMicrophone = true,
            isActiveSpeaker = false,
        ),
    )
}
