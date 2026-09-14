/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.element.android.call.api.ElementCallRoomMember
import io.element.android.call.api.rtc.id.UserId

/**
 * Who an avatar is for. The host's avatar composable decides how to draw it: Element X loads [url]
 * with its own image loader and falls back to its initials avatar, the default here draws initials.
 */
@Immutable
data class ElementCallAvatarData(
    /** The user id, which is what the initial and the colour are derived from when there is nothing else. */
    val id: String,
    val name: String?,
    /** An `mxc://` URL, or null. */
    val url: String?,
)

/** The two sizes the call draws avatars at. */
object ElementCallAvatarSize {
    /** In a strip tile or the floating tile. */
    val Tile: Dp = 56.dp

    /** In the spotlight, the full-bleed tile or picture-in-picture. */
    val Spotlight: Dp = 96.dp
}

/**
 * The avatar for a participant: the room member's profile when the host has loaded it, else an
 * id-derived placeholder that already has the right initial and colour, so it turns into the real
 * avatar without the tile changing shape.
 */
@Composable
fun ElementCallAvatar(
    userId: UserId,
    roomMember: ElementCallRoomMember?,
    size: Dp,
) {
    val data = ElementCallAvatarData(
        id = userId.value,
        name = roomMember?.displayName?.takeIf { it.isNotBlank() },
        url = roomMember?.avatarUrl,
    )
    ElementCallTheme.style.avatar(data, size)
}
