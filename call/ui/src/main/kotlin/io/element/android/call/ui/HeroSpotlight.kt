/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.element.android.call.ui.theme.ElementCallTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap

/**
 * The spotlight over the hero stack: a pager in the model's order showing one hero at a time,
 * exactly the shown one composed and receiving video (R19, R20, R24). The pager is driven from the
 * shown hero's identity, so a stack reordering under it scrolls, without animation, to where that
 * hero now is: identity is the truth and the page index follows (R21). A settled swipe names the
 * hero it landed on (R22); the pager does not wrap (R23).
 *
 * Chrome by orientation (contract B2): a "1 of n" pill and dots in portrait, arrows in landscape.
 * Screen readers step the stack through custom actions in both, and the arrows are focusable too
 * (R25). A vertical drag here never reaches the grid's scrollable (R64).
 */
@Composable
internal fun HeroSpotlight(
    heroIds: ImmutableList<String>,
    shownId: String,
    rect: Rect,
    isFullscreen: Boolean,
    /** Another tile fills the stage: this one is composed under it and receives no video (000 R17). */
    isDimmed: Boolean,
    scrollOffset: FloatState,
    isLandscape: Boolean,
    tilesById: ImmutableMap<String, CallTileData>,
    state: ElementCallScreenState,
) {
    val shownIndex = heroIds.indexOf(shownId).coerceAtLeast(0)
    val currentHeroIds by rememberUpdatedState(heroIds)
    val pagerState = rememberPagerState(initialPage = shownIndex) { currentHeroIds.size }
    LaunchedEffect(shownIndex, heroIds) {
        if (pagerState.currentPage != shownIndex && !pagerState.isScrollInProgress) pagerState.scrollToPage(shownIndex)
    }
    val currentEventSink by rememberUpdatedState(state.eventSink)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            currentHeroIds.getOrNull(page)?.let { currentEventSink(ElementCallScreenEvent.ShowHero(it)) }
        }
    }
    val show = { index: Int -> heroIds.getOrNull(index)?.let { state.eventSink(ElementCallScreenEvent.ShowHero(it)) } != null }
    val nextLabel = stringResource(R.string.element_call_a11y_next_hero)
    val previousLabel = stringResource(R.string.element_call_a11y_previous_hero)
    val fullscreenLabel = stringResource(if (isFullscreen) R.string.element_call_a11y_exit_fullscreen else R.string.element_call_a11y_enter_fullscreen)
    var zoom by remember(isFullscreen) { mutableStateOf(VideoTransform.None) }
    // As for a grid tile: above everything until the way back from fullscreen has landed (000 R7).
    var isRaised by remember { mutableStateOf(isFullscreen) }
    if (isFullscreen) isRaised = true
    val transformable = rememberTransformableState { _, zoomChange, pan, _ ->
        val scale = (zoom.scale * zoomChange).coerceIn(1f, MAX_ZOOM)
        val maxX = (rect.width * scale - rect.width) / 2
        val maxY = (rect.height * scale - rect.height) / 2
        zoom = VideoTransform(scale, Offset((zoom.offset.x + pan.x).coerceIn(-maxX, maxX), (zoom.offset.y + pan.y).coerceIn(-maxY, maxY)))
    }
    Box(
        modifier = Modifier
            .animatedSlot(rect, isSticky = true, scrollOffset = scrollOffset, onArrive = { isRaised = isFullscreen })
            .zIndex(if (isRaised) FULLSCREEN_Z_INDEX else SPOTLIGHT_Z_INDEX)
            .scrollable(rememberScrollableState { it }, Orientation.Vertical)
            .then(if (isFullscreen) Modifier.transformable(transformable) else Modifier)
            .semantics {
                traversalIndex = 0f
                customActions = listOf(
                    CustomAccessibilityAction(nextLabel) { show(shownIndex + 1) },
                    CustomAccessibilityAction(previousLabel) { show(shownIndex - 1) },
                    CustomAccessibilityAction(fullscreenLabel) {
                        state.eventSink(ElementCallScreenEvent.ToggleFullscreen(shownId))
                        true
                    },
                )
            },
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { page -> currentHeroIds.getOrNull(page) ?: page },
            beyondViewportPageCount = 0,
            // A swipe would change the hero under a zoom; the stack is switched from the grid again on the way out.
            userScrollEnabled = !isFullscreen,
        ) { page ->
            val tile = currentHeroIds.getOrNull(page)?.let { tilesById[it] } ?: return@HorizontalPager
            // The pager may keep a neighbouring page composed for a moment; only the shown hero receives video (R24).
            val isShown = tile.tileId == shownId && !isDimmed
            ReportVideoConstraints(
                tile = tile,
                slot = rect,
                hasVideo = state.videoFrames[tile.tileId] != null,
                isLive = isShown,
                eventSink = state.eventSink,
                scaleFactor = if (isFullscreen) zoom.scale.quantisedToPowerOfTwo() else 1,
            )
            ReportLiveToHooks(tile.tileId, isLive = isShown)
            val appearance = if (isFullscreen) CallTileAppearance.Fullscreen else CallTileAppearance.Spotlight
            val fit by animateFloatAsState(targetValue = appearance.fitFor(tile), animationSpec = FIT_SPEC, label = "heroFit")
            // Only the shown page: a neighbour kept composed would report a place off to the side.
            val pictureInPictureSource = pictureInPictureSourceModifier(isShown && tile.tileId == state.pictureInPictureTileId) {
                state.eventSink(ElementCallScreenEvent.SetPictureInPictureSource(it))
            }
            CallTile(
                tile = tile,
                videoFrames = state.videoFrames[tile.tileId],
                appearance = appearance,
                stats = state.tileStats(tile, rect),
                fit = fit,
                showName = !isLandscape && !isFullscreen,
                videoTransform = if (isFullscreen) zoom else VideoTransform.None,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(ElementCallTestTags.tile(tile.tileId))
                    .combinedClickable(
                        onClick = { if (isFullscreen) state.eventSink(ElementCallScreenEvent.ToggleFullscreenChrome) },
                        onDoubleClick = { state.eventSink(ElementCallScreenEvent.ToggleFullscreen(tile.tileId)) },
                        onLongClick = { state.eventSink(ElementCallScreenEvent.ToggleTileStats) },
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    )
                    .then(pictureInPictureSource),
            )
        }
        if (heroIds.size > 1 && !isFullscreen) {
            // Bottom-left in landscape, where the frames put it and where no name pill is; top-right
            // in portrait, where the share's name pill has the bottom-left.
            HeroPositionPill(
                position = shownIndex + 1,
                count = heroIds.size,
                modifier = Modifier
                    .align(if (isLandscape) Alignment.BottomStart else Alignment.TopEnd)
                    .padding(14.dp)
                    .testTag(ElementCallTestTags.HERO_INDICATOR),
            )
            if (isLandscape) {
                HeroArrow(
                    icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    contentDescription = previousLabel,
                    enabled = shownIndex > 0,
                    onClick = { show(shownIndex - 1) },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(8.dp)
                        .testTag(ElementCallTestTags.HERO_PREVIOUS),
                )
                HeroArrow(
                    icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = nextLabel,
                    enabled = shownIndex < heroIds.size - 1,
                    onClick = { show(shownIndex + 1) },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(8.dp)
                        .testTag(ElementCallTestTags.HERO_NEXT),
                )
            } else {
                HeroDots(
                    count = heroIds.size,
                    shownIndex = shownIndex,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun HeroPositionPill(position: Int, count: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.element_call_hero_position, position, count),
        style = ElementCallTheme.typography.bodySmMedium,
        color = ElementCallTheme.colors.onOverlay,
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(ElementCallTheme.colors.overlayScrim)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun HeroDots(count: Int, shownIndex: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            Box(
                modifier = Modifier
                    .size(if (index == shownIndex) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(ElementCallTheme.colors.onOverlay.copy(alpha = if (index == shownIndex) 1f else 0.5f))
                    .border(1.dp, ElementCallTheme.colors.overlayScrim, CircleShape),
            )
        }
    }
}

@Composable
private fun HeroArrow(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = ElementCallTheme.colors.overlayScrim,
            contentColor = ElementCallTheme.colors.onOverlay,
            disabledContainerColor = ElementCallTheme.colors.overlayScrim.copy(alpha = 0.3f),
            disabledContentColor = ElementCallTheme.colors.onOverlay.copy(alpha = 0.4f),
        ),
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}
