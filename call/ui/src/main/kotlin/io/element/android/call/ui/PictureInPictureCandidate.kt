/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import io.element.android.call.api.ElementCallRoomMember
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.id.UserId

/** The one tile a window with room for one thing shows. See [pictureInPictureCandidate]. */
data class PictureInPictureCandidate(
    val id: MatrixRtcTileId,
    val userId: UserId,
    val roomMember: ElementCallRoomMember?,
    val hasVideo: Boolean,
    val isLocal: Boolean,
) {
    val kind: MatrixRtcStreamKind get() = id.kind.videoStreamKind
}

/**
 * What Picture in Picture and the minimised tile show (spec 003 R68): what the spotlight shows;
 * with no spotlight the first tile in order that has video, otherwise the first remote tile; and
 * with nobody else, ourselves - which the spotlight never does, but here the choice is between our
 * own tile and an empty rectangle.
 *
 * One function for both windows, so they cannot disagree, in place of two "first member with
 * video" scans.
 */
fun ElementCallSnapshot.pictureInPictureCandidate(spotlightId: MatrixRtcTileId?): PictureInPictureCandidate? {
    val order = roster.order
    val chosen = spotlightId?.let { id -> order.firstOrNull { it.id == id } }
        ?: order.firstOrNull { roster.detail[it.id]?.hasVideo == true }
        ?: order.firstOrNull()
    if (chosen != null) {
        return PictureInPictureCandidate(
            id = chosen.id,
            userId = chosen.userId,
            roomMember = roomMembers[chosen.userId],
            hasVideo = roster.detail[chosen.id]?.hasVideo == true,
            isLocal = false,
        )
    }
    val own = ownTile ?: return null
    return PictureInPictureCandidate(
        id = own.id,
        userId = own.userId,
        roomMember = roomMembers[own.userId],
        hasVideo = isCameraEnabled,
        isLocal = true,
    )
}
