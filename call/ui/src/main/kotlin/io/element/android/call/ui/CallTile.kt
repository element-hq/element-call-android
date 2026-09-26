/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.element.android.call.api.rtc.MatrixRtcFrameEncryptionState
import io.element.android.call.api.rtc.MatrixRtcReceiveStats
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import io.element.android.call.ui.preview.ElementCallPreview
import io.element.android.call.ui.preview.PreviewsDayNight
import io.element.android.call.ui.theme.ElementCallAvatar
import io.element.android.call.ui.theme.ElementCallAvatarSize
import io.element.android.call.ui.theme.ElementCallTheme
import io.element.android.call.ui.video.CallVideoRenderer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * One tile of the call: a member's video if they are sending any, their avatar if not.
 *
 * The two are the same tile rather than two different ones, because a member turning their camera
 * on and off must not make their tile jump position in the grid - which is what happens when the
 * two cases are separate composables with separate keys.

 */
@Composable
fun CallTile(
    tile: CallTileData,
    videoFrames: Flow<MatrixRtcVideoFrame>?,
    modifier: Modifier = Modifier,
    appearance: CallTileAppearance = CallTileAppearance.Grid,
    /** Non-null when the debug overlay is on. See [CallTileStatsOverlay]. */
    stats: TileStats? = null,
    /**
     * Where between filling the tile and fitting inside it the video is drawn, see [CallVideoRenderer].
     * Chosen by [appearance] unless given: a share in the spotlight fits, a camera there is drawn at
     * [SPOTLIGHT_CAMERA_FIT], a grid tile fills and crops (spec 003 R11, R15, R16).
     */
    fit: Float = appearance.fitFor(tile),
) {
    // One per tile, for the life of the tile. Emphatically *not* keyed on [stats], which is rebuilt on
    // every recomposition: keying on it gave every recomposition a fresh counter, so the overlay read
    // a counter that had just been reset while the renderer went on feeding the original one it had
    // captured. The readout sat at "0x0 @ 0fps" and looked like a dead stream.
    //
    // Created whether or not the overlay is on, because two volatile writes per frame cost nothing
    // and making its existence conditional is what created the coupling in the first place.
    val frameCounter = remember { TileFrameCounter() }
    // Animated because the same tile changes appearance in place, and a corner snapping while the
    // tile is still travelling to its new rectangle reads as a glitch on top of the move.
    val corner by animateDpAsState(
        targetValue = if (appearance == CallTileAppearance.Spotlight) SPOTLIGHT_CORNER else TILE_CORNER,
        label = "tileCorner",
    )
    val shape = RoundedCornerShape(corner)
    val description = tile.accessibilityDescription()
    Box(
        modifier = modifier
            .semantics { contentDescription = description }
            .clip(shape)
            .background(ElementCallTheme.colors.bgSubtlePrimary)
            // The ring is how "who is talking" is answered at a glance, and it is drawn from the
            // SFU's own view of who it can hear rather than from our decoded audio - so it still
            // lights up for a member whose media we cannot decrypt, which is the case worth being
            // able to see. A decoration changes on the tile without moving it (R40).
            .then(
                if (tile.isActiveSpeaker) Modifier.border(2.dp, ElementCallTheme.colors.borderActiveSpeaker, shape) else Modifier
            ),
    ) {
        if (videoFrames != null) {
            CallVideoRenderer(
                frames = videoFrames,
                isMirrored = tile.isVideoMirrored,
                modifier = Modifier.fillMaxSize(),
                frameCounter = frameCounter,
                fit = fit,
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // Falls back to an id-derived avatar when the member list has not loaded yet, rather
                // than showing nothing: the initial and colour come out of the user id, so the
                // placeholder is already stable and turns into the real avatar without the tile
                // changing shape. A tile outside the detail window is drawn exactly this way (R54).
                ElementCallAvatar(
                    userId = tile.userId,
                    roomMember = tile.roomMember,
                    size = if (appearance == CallTileAppearance.Spotlight) ElementCallAvatarSize.Spotlight else ElementCallAvatarSize.Tile,
                )
            }
        }

        NamePill(
            tile = tile,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp),
        )
        if (tile.isHandRaised) {
            HandRaisedBadge(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
            )
        }

        // Top-left, where the name pill and the badge are not. Only ever drawn for a tile with
        // video: the numbers are all about a stream, and a member showing an avatar has none.
        if (stats != null && videoFrames != null) {
            CallTileStatsOverlay(
                counter = frameCounter,
                receiveStats = stats.receiveStats,
                audioStats = stats.audioStats,
                frameEncryption = stats.frameEncryption,
                requestedWidth = stats.requestedWidth,
                requestedHeight = stats.requestedHeight,
                isReachable = tile.isReachable,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp),
            )
        }
    }
}

/**
 * Where the layout has put a tile, which decides how its video is fitted and how large its avatar is.
 *
 * A parameter rather than two composables because the tile has to stay the same keyed composable
 * as it moves between roles - see `CallStage` - so what changes has to be its arguments.
 */
enum class CallTileAppearance {
    /** In the grid: 4:3, the camera filling and cropping it (R11). */
    Grid,

    /** In the spotlight: a share fitted entirely (R15), a camera cropped only partially (R16). */
    Spotlight,
    ;

    internal fun fitFor(tile: CallTileData): Float = when (this) {
        Grid -> 0f
        Spotlight -> if (tile.isScreenShare) 1f else SPOTLIGHT_CAMERA_FIT
    }
}

/**
 * Everything the debug overlay needs that the tile does not already have.
 *
 * Read through the state maps by the layout, which is what knows the tile's stream and its member.
 * [audioStats] is null for a share tile: a screen is nobody's voice.
 */
data class TileStats(
    val receiveStats: MatrixRtcReceiveStats?,
    val audioStats: MatrixRtcReceiveStats?,
    val frameEncryption: MatrixRtcFrameEncryptionState?,
    val requestedWidth: Int,
    val requestedHeight: Int,
)

/**
 * The name and, for a person, whether they can be heard: a microphone icon, red when muted. A share
 * tile is named for its owner's screen and carries the share icon instead.
 *
 * A tile outside the detail window has a name but no microphone state, so it shows no icon at all
 * rather than a guess (R54).
 */
@Composable
private fun NamePill(tile: CallTileData, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(ElementCallTheme.colors.overlayScrim)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val icon = when {
            tile.isScreenShare -> ElementCallTheme.icons.shareScreenActive
            !tile.hasDetail -> null
            tile.isMuted -> ElementCallTheme.icons.microphoneOff
            else -> ElementCallTheme.icons.microphoneOn
        }
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (tile.isMuted && !tile.isScreenShare) ElementCallTheme.colors.iconCritical else ElementCallTheme.colors.onOverlay,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = if (tile.isScreenShare) stringResource(R.string.element_call_shared_screen_name, tile.displayName) else tile.displayName,
            style = ElementCallTheme.typography.bodySmMedium,
            color = ElementCallTheme.colors.onOverlay,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HandRaisedBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(ElementCallTheme.colors.overlayScrim),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = ElementCallTheme.icons.handRaised,
            contentDescription = null,
            tint = ElementCallTheme.colors.onOverlay,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** The name and the states a screen reader has to say, since none of the badges say them (R69). */
@Composable
private fun CallTileData.accessibilityDescription(): String {
    val states = buildList {
        if (isScreenShare) add(stringResource(R.string.element_call_a11y_sharing_screen))
        if (hasDetail && !isScreenShare && isMuted) add(stringResource(R.string.element_call_a11y_muted))
        if (isActiveSpeaker) add(stringResource(R.string.element_call_a11y_speaking))
        if (isHandRaised) add(stringResource(R.string.element_call_a11y_hand_raised))
    }
    return (listOf(displayName) + states).joinToString(", ")
}

/** A camera in the spotlight is drawn halfway between fill and fit (spec 003 R16, contract B7). */
const val SPOTLIGHT_CAMERA_FIT = 0.5f

private val TILE_CORNER = 12.dp

/** Edge to edge, so square (contract B3); the grid keeps its margins and its corners. */
private val SPOTLIGHT_CORNER = 0.dp

@PreviewsDayNight
@Composable
internal fun CallTilePreview(@PreviewParameter(CallTileDataPreviewParam::class) tile: CallTileData) = ElementCallPreview {
    CallTile(
        tile = tile,
        videoFrames = if (tile.hasVideo) emptyFlow() else null,
        modifier = Modifier.size(width = 180.dp, height = 135.dp),
    )
}
