/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.animation.core.Animatable
import io.element.android.call.ui.video.CallVideoRenderer
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.call.impl.NativeCallSnapshot
import io.element.android.libraries.designsystem.components.avatar.Avatar
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.components.avatar.AvatarType
import io.element.android.call.api.rtc.id.UserId
import io.element.android.libraries.matrix.ui.model.getAvatarData
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A minimized *video* call: a small draggable tile floating over the app.
 *
 * The docked bar is the right answer for an audio call and the wrong one for a video call, where the
 * whole point of the call is a picture. Android's own picture-in-picture cannot serve this: PiP is an
 * out-of-app mode, so the system only shrinks the Activity once the user has left the app entirely.
 * Floating over *our own* content while the user carries on scrolling a timeline is something only
 * the app can draw, which is why this is hand-rolled rather than delegated. Native PiP still covers
 * the moment the user leaves - see `PictureInPictureCall` - and the two hand over to each other.
 *
 * Draggable and corner-snapping, because a fixed thumbnail eventually covers the one thing the user
 * wants to read, and because every messenger that has this behaves the same way, so the gesture needs
 * no teaching.
 *
 * No controls on the tile. At this size they would be under the minimum touch target and would
 * compete with the drag gesture; the ongoing-call notification carries mute and hang up, and tapping
 * expands back to the full screen where the real controls are.
 */
@Composable
fun FloatingCallTile(
    call: NativeCallSnapshot,
    videoFrames: (memberId: String, kind: MatrixRtcStreamKind) -> Flow<MatrixRtcVideoFrame>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tile = call.floatingTile() ?: return

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val margin = with(density) { TILE_MARGIN.toPx() }
        val maxX = (constraints.maxWidth - with(density) { TILE_WIDTH.toPx() } - margin).coerceAtLeast(margin)
        val maxY = (constraints.maxHeight - with(density) { TILE_HEIGHT.toPx() } - margin).coerceAtLeast(margin)

        // Top right to start with: the bottom of a screen is where the composer and the navigation
        // bar live, and the top left is where a back button and a title usually are.
        val offsetX = remember { Animatable(maxX) }
        val offsetY = remember { Animatable(margin) }
        val scope = rememberCoroutineScope()

        // Rotation and window changes move the walls, so anything already parked against one has to
        // be brought back inside them rather than left off-screen.
        LaunchedEffect(maxX, maxY) {
            offsetX.snapTo(offsetX.value.coerceIn(margin, maxX))
            offsetY.snapTo(offsetY.value.coerceIn(margin, maxY))
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
                .size(width = TILE_WIDTH, height = TILE_HEIGHT)
                .shadow(elevation = 8.dp, shape = RoundedCornerShape(TILE_CORNER))
                .clip(RoundedCornerShape(TILE_CORNER))
                .background(ElementTheme.colors.bgSubtlePrimary)
                .pointerInput(maxX, maxY) {
                    detectDragGestures(
                        onDrag = { change, delta ->
                            change.consume()
                            scope.launch {
                                offsetX.snapTo((offsetX.value + delta.x).coerceIn(margin, maxX))
                                offsetY.snapTo((offsetY.value + delta.y).coerceIn(margin, maxY))
                            }
                        },
                        // Snapped to whichever side is nearer rather than left where it was dropped,
                        // so the tile always ends up flush and never half over the content.
                        onDragEnd = {
                            val nearestEdge = if (offsetX.value + TILE_WIDTH.toPx() / 2 < size.width / 2) margin else maxX
                            scope.launch { offsetX.animateTo(nearestEdge, SNAP_SPEC) }
                        },
                    )
                }
                .clickable(onClick = onClick),
        ) {
            val frames = tile.memberId?.let { videoFrames(it, tile.kind) }
            if (frames != null) {
                CallVideoRenderer(
                    frames = frames,
                    isMirrored = tile.isMirrored,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Avatar(
                        avatarData = tile.avatarData,
                        avatarType = AvatarType.User,
                    )
                }
            }
        }
    }
}

/** What the floating tile draws: one member's stream, or their face when there is no video. */
private class FloatingTile(
    val memberId: String?,
    val kind: MatrixRtcStreamKind,
    val isMirrored: Boolean,
    val avatarData: AvatarData,
)

/**
 * Who the floating tile shows, following the same order of preference as the spotlight: a shared
 * screen, then whoever is being spotlighted, then anyone else.
 *
 * Falls back to *ourselves* when there is nobody else yet, which the spotlight deliberately does not.
 * The spotlight refuses because the full screen already draws us in the strip, so spotlighting us
 * would draw the same person twice - there is no strip here, so the choice is between our own tile
 * and an empty rectangle.
 */
private fun NativeCallSnapshot.floatingTile(): FloatingTile? {
    fun avatarOf(userId: UserId) =
        roomMembers[userId]?.getAvatarData(AvatarSize.CallTile)
            ?: AvatarData(id = userId.value, name = null, url = null, size = AvatarSize.CallTile)

    val sharer = participants.firstOrNull { participant ->
        !participant.isLocal && participant.streams.any { it.kind == MatrixRtcStreamKind.SCREEN_SHARE && !it.isMuted }
    }
    if (sharer != null) {
        return FloatingTile(sharer.memberId, MatrixRtcStreamKind.SCREEN_SHARE, isMirrored = false, avatarData = avatarOf(sharer.userId))
    }

    val remote = participants.firstOrNull { it.memberId == spotlightMemberId && !it.isLocal }
        ?: participants.firstOrNull { !it.isLocal }
    val chosen = remote ?: participants.firstOrNull { it.isLocal } ?: return null
    val hasCamera = chosen.streams.any { it.kind == MatrixRtcStreamKind.CAMERA && !it.isMuted }
    return FloatingTile(
        memberId = chosen.memberId.takeIf { hasCamera },
        kind = MatrixRtcStreamKind.CAMERA,
        isMirrored = chosen.isLocal && isFrontCamera,
        avatarData = avatarOf(chosen.userId),
    )
}

private val TILE_WIDTH = 100.dp
private val TILE_HEIGHT = 140.dp
private val TILE_MARGIN = 12.dp
private val TILE_CORNER = 12.dp

private val SNAP_SPEC = spring<Float>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMediumLow,
)
