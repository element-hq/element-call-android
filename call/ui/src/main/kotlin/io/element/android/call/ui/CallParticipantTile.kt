/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.animation.core.animateDpAsState
import io.element.android.call.ui.video.CallVideoRenderer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.components.avatar.Avatar
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.components.avatar.AvatarType
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.ui.model.getAvatarData
import io.element.android.call.api.rtc.MatrixRtcFrameEncryptionState
import io.element.android.call.api.rtc.MatrixRtcReceiveStats
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.coroutines.flow.Flow

/**
 * One member of the call: their video if they are sending any, their avatar if not.
 *
 * The two are the same tile rather than two different ones, because a member turning their camera
 * on and off must not make their tile jump position in the strip - which is what happens when the
 * two cases are separate composables with separate keys.
 */
@Composable
fun CallParticipantTile(
    participant: CallParticipant,
    videoFrames: Flow<MatrixRtcVideoFrame>?,
    isSpotlight: Boolean,
    modifier: Modifier = Modifier,
    appearance: CallTileAppearance = CallTileAppearance.Card,
    /** Non-null when the debug overlay is on. See [CallTileStatsOverlay]. */
    stats: TileStats? = null,
) {
    // One per tile, for the life of the tile. Emphatically *not* keyed on [stats], which is rebuilt on
    // every recomposition: keying on it gave every recomposition a fresh counter, so the overlay read
    // a counter that had just been reset while the renderer went on feeding the original one it had
    // captured. The readout sat at "0x0 @ 0fps" and looked like a dead stream.
    //
    // Created whether or not the overlay is on, because two volatile writes per frame cost nothing
    // and making its existence conditional is what created the coupling in the first place.
    val frameCounter = remember { TileFrameCounter() }
    // Animated because the same tile changes appearance in place - the remote's card becomes the
    // full-bleed view when a third member leaves - and a corner snapping from rounded to square while
    // the tile is still travelling to its new rectangle reads as a glitch on top of the move.
    val corner by animateDpAsState(
        targetValue = if (appearance == CallTileAppearance.FullBleed) 0.dp else TILE_CORNER,
        label = "tileCorner",
    )
    val shape = RoundedCornerShape(corner)
    Box(
        modifier = modifier
            .clip(shape)
            .background(ElementTheme.colors.bgSubtlePrimary)
            // The ring is how "who is talking" is answered at a glance in a grid, and it is drawn
            // from the SFU's own view of who it can hear rather than from our decoded audio - so it
            // still lights up for a member whose media we cannot decrypt, which is the case worth
            // being able to see. Only in a grid: with two people there is nobody to tell apart.
            //
            // The thumbnail gets a hairline instead, so it has an edge when both cameras are off
            // and it would otherwise be an avatar floating over the other person's background.
            .then(
                when {
                    participant.isActiveSpeaker && appearance == CallTileAppearance.Card ->
                        Modifier.border(2.dp, ElementTheme.colors.borderSuccessSubtle, shape)
                    appearance == CallTileAppearance.Thumbnail ->
                        Modifier.border(1.dp, ElementTheme.colors.borderInteractiveSecondary, shape)
                    else -> Modifier
                }
            ),
    ) {
        if (videoFrames != null) {
            CallVideoRenderer(
                frames = videoFrames,
                isMirrored = participant.isVideoMirrored,
                modifier = Modifier.fillMaxSize(),
                frameCounter = frameCounter,
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val isLarge = isSpotlight || appearance == CallTileAppearance.FullBleed
                Avatar(
                    avatarData = participant.avatarData(if (isLarge) AvatarSize.CallSpotlight else AvatarSize.CallTile),
                    avatarType = AvatarType.User,
                )
            }
        }

        when (appearance) {
            CallTileAppearance.Card -> NamePill(
                participant = participant,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp),
            )
            // The name is in the top bar and the only other person is us, so all that is left to
            // say about them is whether they can be heard.
            CallTileAppearance.FullBleed -> if (participant.isMuted) {
                MutedBadge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                )
            }
            // Our own mute state is on the button we set it with.
            CallTileAppearance.Thumbnail -> Unit
        }

        // Top-left, where the name pill and the mute badge are not. Only ever drawn for a tile with
        // video: the numbers are all about a stream, and a member showing an avatar has none.
        if (stats != null && videoFrames != null) {
            CallTileStatsOverlay(
                counter = frameCounter,
                receiveStats = stats.receiveStats,
                frameEncryption = stats.frameEncryption,
                requestedWidth = stats.requestedWidth,
                requestedHeight = stats.requestedHeight,
                isReachable = participant.isReachable,
                hasMicrophone = participant.hasMicrophone,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp),
            )
        }
    }
}

/**
 * How much of its own chrome a tile draws, which depends on where the layout has put it.
 *
 * One parameter rather than a set of booleans because the three are fixed combinations: nothing
 * ever wants a name pill without a speaker ring. And a parameter rather than three composables
 * because the tile has to stay the same keyed composable as it moves between roles - see
 * `CallTileLayout` - so what changes has to be its arguments.
 */
enum class CallTileAppearance {
    /** In a grid: rounded, named, ringed when talking. */
    Card,

    /** Filling the screen behind the chrome in a one-to-one call: square, unnamed, a mute badge if muted. */
    FullBleed,

    /** Our own thumbnail over the other person in a one-to-one call: rounded and otherwise bare. */
    Thumbnail,
}

/**
 * Everything the debug overlay needs that the tile does not already have.
 *
 * A single parameter rather than five, so that turning the overlay off is one null rather than a set
 * of arguments the tile has to carry around whether or not anybody is looking at them.
 */
data class TileStats(
    val receiveStats: MatrixRtcReceiveStats?,
    val frameEncryption: MatrixRtcFrameEncryptionState?,
    val requestedWidth: Int,
    val requestedHeight: Int,
)

/**
 * Who this is, and whether they can be heard.
 *
 * The mute state lives on the pill rather than in a corner of its own because the two are read
 * together - "is that person muted" is a question about a person, not about a tile.
 */
@Composable
private fun NamePill(
    participant: CallParticipant,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(PILL_BACKGROUND)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // A screen has no microphone, so a mute icon on it would be answering a question nobody
        // asked - and the person it belongs to is in the strip with a real one.
        Icon(
            imageVector = when {
                participant.isScreenShare -> CompoundIcons.ShareScreenSolid()
                participant.isMuted -> CompoundIcons.MicOffSolid()
                else -> CompoundIcons.MicOnSolid()
            },
            contentDescription = null,
            tint = if (participant.isMuted && !participant.isScreenShare) {
                ElementTheme.colors.iconCriticalPrimary
            } else {
                Color.White
            },
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = if (participant.isScreenShare) {
                stringResource(CommonStrings.screen_call_shared_screen_name, participant.displayName)
            } else {
                participant.displayName
            },
            style = ElementTheme.typography.fontBodySmMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** That the other person cannot be heard, without the name the pill would put beside it. */
@Composable
private fun MutedBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(BADGE_SIZE)
            .clip(CircleShape)
            .background(PILL_BACKGROUND),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = CompoundIcons.MicOffSolid(),
            contentDescription = stringResource(CommonStrings.a11y_microphone_muted),
            tint = ElementTheme.colors.iconCriticalPrimary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Falls back to an id-derived avatar when the member list has not loaded yet, rather than showing
 * nothing: the initial and colour come out of the user id, so the placeholder is already stable and
 * turns into the real avatar without the tile changing shape.
 */
private fun CallParticipant.avatarData(size: AvatarSize) = roomMember
    ?.getAvatarData(size)
    ?: AvatarData(id = userId.value, name = null, url = null, size = size)

private val TILE_CORNER = 12.dp
private val BADGE_SIZE = 28.dp

/** Fixed rather than themed: it sits over video, which is not a themed surface. */
private val PILL_BACKGROUND = Color(0xCC15191E)
