/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.element.android.call.api.ElementCallRoomMember
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import io.element.android.call.api.rtc.id.UserId
import io.element.android.call.ui.preview.ElementCallPreview
import io.element.android.call.ui.preview.PreviewsDayNight
import io.element.android.call.ui.theme.ElementCallAvatar
import io.element.android.call.ui.theme.ElementCallAvatarSize
import io.element.android.call.ui.theme.ElementCallTheme
import io.element.android.call.ui.video.CallVideoRenderer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
 * the moment the user leaves - see `ElementCallPictureInPictureContent` - and the two hand over to each other.
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
fun ElementCallFloatingTile(
    call: ElementCallSnapshot,
    videoFrames: (memberId: String, kind: MatrixRtcStreamKind) -> Flow<MatrixRtcVideoFrame>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tile = call.floatingTile() ?: return
    var videoSize by remember(tile.memberId, tile.kind) { mutableStateOf<IntSize?>(null) }
    val targetSize = floatingTileSize(videoSize.takeIf { tile.memberId != null })
    val tileWidth by animateDpAsState(targetSize.width, RESIZE_SPEC, label = "floatingTileWidth")
    val tileHeight by animateDpAsState(targetSize.height, RESIZE_SPEC, label = "floatingTileHeight")

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val margin = with(density) { TILE_MARGIN.toPx() }
        val containerWidth = constraints.maxWidth
        val tileWidthPx = with(density) { tileWidth.toPx() }
        val maxX = (containerWidth - tileWidthPx - margin).coerceAtLeast(margin)
        val maxY = (constraints.maxHeight - with(density) { tileHeight.toPx() } - margin).coerceAtLeast(margin)
        // The drag outlives any one size, so it reads the walls as they are now rather than restarting.
        val currentMaxX by rememberUpdatedState(maxX)
        val currentMaxY by rememberUpdatedState(maxY)
        val currentTileWidthPx by rememberUpdatedState(tileWidthPx)

        // Top right to start with: the bottom of a screen is where the composer and the navigation
        // bar live, and the top left is where a back button and a title usually are.
        val offsetX = remember { Animatable(maxX) }
        val offsetY = remember { Animatable(margin) }
        val scope = rememberCoroutineScope()
        val previousMaxX = remember { mutableFloatStateOf(maxX) }

        // Rotation, window and tile size changes move the walls: a tile parked on the right stays
        // flush with it, anything else is brought back inside.
        LaunchedEffect(maxX, maxY) {
            val wasOnRight = offsetX.value >= previousMaxX.floatValue
            previousMaxX.floatValue = maxX
            offsetX.snapTo(if (wasOnRight) maxX else offsetX.value.coerceIn(margin, maxX))
            offsetY.snapTo(offsetY.value.coerceIn(margin, maxY))
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
                .size(width = tileWidth, height = tileHeight)
                .shadow(elevation = 8.dp, shape = RoundedCornerShape(TILE_CORNER))
                .clip(RoundedCornerShape(TILE_CORNER))
                .background(ElementCallTheme.colors.bgSubtlePrimary)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, delta ->
                            change.consume()
                            scope.launch {
                                offsetX.snapTo((offsetX.value + delta.x).coerceIn(margin, currentMaxX))
                                offsetY.snapTo((offsetY.value + delta.y).coerceIn(margin, currentMaxY))
                            }
                        },
                        // Snapped to whichever side is nearer rather than left where it was dropped,
                        // so the tile always ends up flush and never half over the content.
                        //
                        // Measured against the *container's* width, not `size.width`: inside this
                        // scope `size` is the tile's own, and a tile compared with itself is never
                        // past the middle, so it always snapped back to the right. The instrumented
                        // drag test is what found that.
                        onDragEnd = {
                            val nearestEdge = if (offsetX.value + currentTileWidthPx / 2 < containerWidth / 2) margin else currentMaxX
                            scope.launch { offsetX.animateTo(nearestEdge, SNAP_SPEC) }
                        },
                    )
                }
                .clickable(onClick = onClick)
                .testTag(ElementCallTestTags.FLOATING_TILE),
        ) {
            val frames = tile.memberId?.let { videoFrames(it, tile.kind) }
            if (frames != null) {
                CallVideoRenderer(
                    frames = frames,
                    isMirrored = tile.isMirrored,
                    modifier = Modifier.fillMaxSize(),
                    onVideoSizeChange = { videoSize = it },
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ElementCallAvatar(
                        userId = tile.userId,
                        roomMember = tile.roomMember,
                        size = ElementCallAvatarSize.Tile,
                    )
                }
            }
        }
    }
}

/** What the floating tile draws: one member's stream, or their face when there is no video. */
private data class FloatingTile(
    val memberId: String?,
    val kind: MatrixRtcStreamKind,
    val isMirrored: Boolean,
    val userId: UserId,
    val roomMember: ElementCallRoomMember?,
)

/**
 * Who the floating tile shows: the spotlight, so a shared screen when there is one and otherwise the
 * head of the core's ranking.
 *
 * Falls back to *ourselves* when there is nobody else yet, which the spotlight deliberately does not.
 * The spotlight refuses because the full screen already draws us in the strip, so spotlighting us
 * would draw the same person twice - there is no strip here, so the choice is between our own tile
 * and an empty rectangle.
 */
private fun ElementCallSnapshot.floatingTile(): FloatingTile? {
    val remote = tiles.firstOrNull()
    val chosen = remote ?: ownTile ?: return null
    val isLocal = remote == null
    val hasVideo = if (isLocal) isCameraEnabled else chosen.hasVideo
    return FloatingTile(
        memberId = chosen.id.memberId.takeIf { hasVideo },
        kind = chosen.id.kind.videoStreamKind,
        isMirrored = isLocal && isFrontCamera,
        userId = chosen.userId,
        roomMember = roomMembers[chosen.userId],
    )
}

/**
 * The tile's size for a video of [videoSize]: its longer side fixed, the other following the video,
 * so nothing is cropped away. Clamped so an unusual screen share cannot become a sliver.
 */
internal fun floatingTileSize(videoSize: IntSize?): DpSize {
    val aspect = videoSize
        ?.takeIf { it.width > 0 && it.height > 0 }
        ?.let { it.width.toFloat() / it.height }
        ?.coerceIn(MIN_ASPECT, MAX_ASPECT)
        ?: DEFAULT_ASPECT
    return if (aspect >= 1f) {
        DpSize(width = TILE_LONG_SIDE, height = TILE_LONG_SIDE / aspect)
    } else {
        DpSize(width = TILE_LONG_SIDE * aspect, height = TILE_LONG_SIDE)
    }
}

private val TILE_LONG_SIDE = 140.dp
private const val DEFAULT_ASPECT = 100f / 140f
private const val MIN_ASPECT = 9f / 16f
private const val MAX_ASPECT = 16f / 9f
private val TILE_MARGIN = 12.dp
private val TILE_CORNER = 12.dp

private val RESIZE_SPEC = spring<Dp>(stiffness = Spring.StiffnessMediumLow)

private val SNAP_SPEC = spring<Float>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

@PreviewsDayNight
@Composable
internal fun ElementCallFloatingTilePreview(
    @PreviewParameter(MinimizedElementCallSnapshotPreviewParam::class) call: ElementCallSnapshot,
) = ElementCallPreview(fillMaxSize = true) {
    ElementCallFloatingTile(
        call = call,
        videoFrames = { _, _ -> emptyFlow() },
        onClick = {},
    )
}
