/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.call.api.ElementCallRoomMember
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.personTile

/** One tile in each of the states it can be drawn in, so a change to any badge or fallback is a screenshot diff. */
open class CallTileDataPreviewParam : PreviewParameterProvider<CallTileData> {
    override val values: Sequence<CallTileData>
        get() = sequenceOf(
            // Us, camera off: the avatar, and no room profile yet so the initial comes from the user id.
            aLocalParticipant().toTile(),
            // Someone talking with their camera on.
            aRemoteParticipant().toTile(roomMember = A_BOB).copy(hasVideo = true, isActiveSpeaker = true),
            // Muted, with a name from the room.
            aRemoteParticipant(isMuted = true).toTile(roomMember = A_BOB),
            // Not reachable: the core has them in the slot but the transport does not see them.
            aRemoteParticipant(isReachable = false).toTile(roomMember = A_BOB),
            // A shared screen: video, no badges, drawn as a spotlight.
            aRemoteParticipant().toTile(roomMember = A_BOB).copy(streamKind = MatrixRtcStreamKind.SCREEN_SHARE, hasVideo = true),
        )
}

private val A_BOB = ElementCallRoomMember(
    userId = aRemoteParticipant().userId,
    displayName = "Bob",
    avatarUrl = null,
)

private fun io.element.android.call.api.rtc.MatrixRtcParticipant.toTile(roomMember: ElementCallRoomMember? = null) = personTile().toCallTileData(
    roomMembers = roomMember?.let { mapOf(it.userId to it) } ?: emptyMap(),
    isLocal = isLocal,
    isFrontCamera = true,
)
