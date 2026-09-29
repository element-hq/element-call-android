/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.element.android.call.api.rtc.MatrixRtcDetailWindow
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcStreamRef
import io.element.android.call.api.rtc.MatrixRtcVideoConstraints
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import io.element.android.call.ui.theme.ElementCallTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The stage: every composed tile, placed by [arrangement], scrolling vertically under a sticky
 * spotlight (spec 003).
 *
 * One composable per tile, keyed by identity, so a rank change is a re-placement and never a
 * remount: the tile's renderer, and the GL surface under it, survive every move (R37). What is
 * composed is bounded by distance from the viewport, never by roster size (R47): a tile within one
 * viewport of the visible area exists and is subscribed; further away it is released after a
 * linger and not composed at all (R48, R56, R58). The same rects drive the composed set, the
 * detail window the core is asked for (R52, R53) and the constraints each stream is sent (R57), so
 * the three cannot disagree.
 *
 */
@Composable
internal fun CallStage(
    state: ElementCallScreenState,
    /** How much of the stage's bottom the control bar floats over, which the last row scrolls clear of (R43, R44). */
    controlsClearance: Dp,
    modifier: Modifier = Modifier,
    arrangement: CallStageArrangement = CallStageArrangement.RankedGrid,
) {
    val scrollOffset = remember { mutableFloatStateOf(0f) }
    val currentLayout = remember { LayoutRef() }
    val scrollable = rememberScrollableState { delta ->
        val max = currentLayout.value?.maxScroll(currentLayout.viewportHeight) ?: 0f
        val target = (scrollOffset.floatValue + delta).coerceIn(0f, max)
        val consumed = target - scrollOffset.floatValue
        scrollOffset.floatValue = target
        // What was actually consumed rather than what was asked for: the difference is what tells
        // a fling it has reached the end.
        consumed
    }
    // The stage clips to its bounds so a grid tile passing under the spotlight is still drawn up to
    // the edge (R27). Screen readers read the spotlight first, then the grid in order (R69).
    BoxWithConstraints(
        modifier = modifier
            .clipToBounds()
            .semantics { isTraversalGroup = true }
            .scrollable(scrollable, Orientation.Vertical, reverseDirection = true),
    ) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        // Nothing is drawn into an area with no room in it: a tile composed at nothing and then
        // grown would animate in from the corner, and would build a GL renderer nobody can see.
        if (width <= 0f || height <= 0f) return@BoxWithConstraints
        val density = LocalDensity.current
        val metrics = remember(width, height, density, controlsClearance) {
            with(density) {
                CallStageMetrics(
                    width = width,
                    height = height,
                    gap = TILE_SPACING.toPx(),
                    margin = TILE_SPACING.toPx(),
                    controlsClearance = controlsClearance.toPx(),
                )
            }
        }
        val spotlightTileId = state.spotlightTileId
        // The listen-mode speaker is a keyed sibling like any grid tile, so promotion is a slide and
        // never a remount (R37); the hero stack is a pager over tiles that are never in the grid (R17).
        val stickyTileId = (state.spotlight as? CallSpotlight.Choice.Speaker)?.tileId
        val gridTileIds = state.gridTiles.map { it.tileId }
        val heroIds = state.heroes
        val layout = remember(gridTileIds, spotlightTileId, heroIds, metrics, arrangement) {
            arrangement.compute(CallStageLayout.Input(gridTileIds, spotlightTileId, heroIds, metrics))
        }
        currentLayout.value = layout
        currentLayout.viewportHeight = height

        // Someone leaving can shorten the grid past the offset; ease back to the new end rather than
        // showing an empty area below the last row (R42). A swipe in the meantime takes precedence.
        LaunchedEffect(layout.contentHeight) {
            val excess = scrollOffset.floatValue - layout.maxScroll(height)
            if (excess > 0f) scrollable.animateScrollBy(-excess)
        }

        // Which tiles are on screen, within half a viewport of it, and within one viewport of it -
        // as sets that only change when a tile crosses an edge, so scrolling by a pixel recomposes
        // nothing and a tile crossing recomposes the tiles whose state changed.
        val viewport = { Rect(0f, scrollOffset.floatValue, width, scrollOffset.floatValue + height) }
        val visibleIds by remember(layout) { derivedStateOf { layout.tilesWithin(viewport(), reach = 0f) } }
        val nearIds by remember(layout) { derivedStateOf { layout.tilesWithin(viewport(), reach = height * CallTileVisibility.LIVE_HYSTERESIS) } }
        val bandIds by remember(layout) { derivedStateOf { layout.tilesWithin(viewport(), reach = height * CallTileVisibility.BAND_REACH) } }

        // After the stage flips between portrait and landscape, the grid tile that was first visible
        // before it is still visible (R66): the offset is re-seated on its row rather than reset.
        val firstVisibleId = remember { StringRef() }
        firstVisibleId.value = visibleIds.minByOrNull { layout.tiles.getValue(it).top } ?: firstVisibleId.value
        val isLandscape = metrics.isLandscape
        val seenOrientation = remember { BooleanRef(isLandscape) }
        LaunchedEffect(isLandscape) {
            if (seenOrientation.value == isLandscape) return@LaunchedEffect
            seenOrientation.value = isLandscape
            val row = firstVisibleId.value?.let { layout.tiles[it]?.top } ?: return@LaunchedEffect
            val gridTop = layout.tiles.values.minOf { it.top }
            scrollOffset.floatValue = (row - gridTop).coerceIn(0f, layout.maxScroll(height))
        }

        // A tile that leaves the band is paused at once by its own effect and released, then
        // uncomposed, only after a linger - so a tile bouncing at the edge of the band does not have
        // its stream torn down and re-established (R58). Re-entering inside the linger cancels it.
        val retainedIds = remember { mutableStateMapOf<String, Unit>() }
        val lingerScope = rememberCoroutineScope()
        val lingerJobs = remember { mutableMapOf<String, Job>() }
        val lastBand = remember { SetRef() }
        val lastShown = remember { StringRef() }
        val currentEventSink by rememberUpdatedState(state.eventSink)
        val tilesById = remember(state.tiles) { state.tiles.associateBy { it.tileId }.toImmutableMap() }
        LaunchedEffect(bandIds, spotlightTileId) {
            fun constrain(id: String, constraints: MatrixRtcVideoConstraints) {
                val tile = tilesById[id] ?: return
                if (!tile.isLocal && tile.hasVideo) {
                    currentEventSink(ElementCallScreenEvent.SetVideoConstraints(tile.memberId, tile.streamKind, constraints))
                }
            }
            val back = bandIds - lastBand.value + setOfNotNull(spotlightTileId)
            back.forEach { id ->
                lingerJobs.remove(id)?.cancel()
                retainedIds.remove(id)
            }
            // A hero no longer shown is hidden by the arrangement, so it is outside the band and
            // released after the linger like any other (R24); its page is gone, so it is paused here.
            // Only the hero that was shown: one that arrived unshown was never subscribed.
            val unshown = lastShown.value?.takeIf { it != spotlightTileId && it !in bandIds }
            unshown?.let { constrain(it, MatrixRtcVideoConstraints.Paused) }
            val gone = lastBand.value - bandIds - setOfNotNull(spotlightTileId) + setOfNotNull(unshown)
            gone.forEach { id ->
                retainedIds[id] = Unit
                lingerJobs[id] = lingerScope.launch {
                    delay(RELEASE_LINGER_MS)
                    retainedIds.remove(id)
                    lingerJobs.remove(id)
                    constrain(id, MatrixRtcVideoConstraints.Released)
                }
            }
            lastBand.value = bandIds
            lastShown.value = spotlightTileId
        }
        val hiddenIds = layout.hiddenTileIds
        val composedGridIds by remember(layout) { derivedStateOf { bandIds + retainedIds.keys } }

        // The same set, told to the call, which polls statistics for these tiles and no others
        // (R59); and the detail window, derived from it (R52 to R55). Ranks are looked up in the
        // order, never taken from grid positions: the grid has our tile first and the heroes
        // removed, so a grid index is off by one per hero.
        LaunchedEffect(composedGridIds, bandIds, spotlightTileId, state.tiles) {
            val composed = state.tiles.filter { it.tileId in composedGridIds || it.tileId == spotlightTileId }
            currentEventSink(ElementCallScreenEvent.SetComposedTiles(composed.map { it.id }.toSet()))
            // The range is the band's, not the linger's: a tile kept only for its stream to settle
            // needs no record, and a range spanning the old band and the new one after a long
            // scroll would declare most of the call (R52's named failure).
            val remoteOrder = state.tiles.filterNot { it.isLocal }
            val ranks = remoteOrder.withIndex().filter { it.value.tileId in bandIds }.map { it.index }
            val range = if (ranks.isEmpty()) IntRange.EMPTY else ranks.min()..ranks.max()
            // WORKAROUND, spec 003 R6 with R52: `speaking` is on the tile record and not on the
            // reference, so once the band has scrolled away from the head of the order the layout
            // cannot see who is speaking. The head's identities are named in the window until the
            // core puts `speaking` on the reference (feature-hq core feedback, 2026-09-26); then
            // the spotlight reads it from the order and this line goes.
            val head = remoteOrder.take(HEAD_RANGE.last + 1).map { it.id }
            val also = (listOfNotNull(composed.firstOrNull { it.tileId == spotlightTileId }?.id) + head).toSet()
            currentEventSink(ElementCallScreenEvent.SetDetailWindow(MatrixRtcDetailWindow(ranks = range, also = also)))
        }

        // Where each tile was last placed, so a leaver keeps fading where it was while the others
        // close over the gap (the arrangement drops them at once).
        val lastSlots = remember { mutableMapOf<String, TileSlot>() }
        layout.tiles.forEach { (tileId, rect) -> lastSlots[tileId] = TileSlot(rect, isSticky = false) }
        val spotlightRect = layout.spotlight
        if (stickyTileId != null && spotlightRect != null) lastSlots[stickyTileId] = TileSlot(spotlightRect, isSticky = true)

        // Who has just left and has not finished fading out. Their tile plays them out and removes
        // itself when it has; parked beyond the band it does so in a frame.
        val leavers = remember { mutableStateMapOf<String, CallTileData>() }
        val lastTiles = remember { TilesRef() }
        LaunchedEffect(state.tiles) {
            val present = state.tiles.map { it.tileId }.toSet()
            lastTiles.value.forEach { tile -> if (tile.tileId !in present) leavers[tile.tileId] = tile }
            present.forEach { leavers.remove(it) }
            lastTiles.value = state.tiles
        }

        val hooks = LocalCallStageTestHooks.current
        if (hooks != null) {
            SideEffect {
                hooks.isMounted = true
                hooks.layout = layout
                hooks.stageSize = IntSize(width.roundToInt(), height.roundToInt())
                hooks.scrollOffset = { scrollOffset.floatValue }
                hooks.composedGridIds = composedGridIds
                hooks.spotlightTileId = spotlightTileId
                hooks.heroes = heroIds
                hooks.eventSink = state.eventSink
                hooks.scrollTo = { target -> scrollOffset.floatValue = target.coerceIn(0f, layout.maxScroll(height)) }
            }
            DisposableEffect(Unit) {
                onDispose {
                    hooks.isMounted = false
                    hooks.layout = null
                    hooks.liveIds.clear()
                }
            }
        }

        val gridIndex = remember(gridTileIds) { gridTileIds.withIndex().associate { it.value to it.index } }
        @Composable
        fun Placed(tile: CallTileData, isPresent: Boolean) {
            val slot = lastSlots[tile.tileId] ?: return
            val isLive = if (slot.isSticky) true else tile.tileId in visibleIds
            val isNear = slot.isSticky || tile.tileId in nearIds
            PlacedTile(
                tile = tile,
                videoFrames = state.videoFrames[tile.tileId],
                appearance = if (slot.isSticky) CallTileAppearance.Spotlight else CallTileAppearance.Grid,
                slot = slot,
                scrollOffset = scrollOffset,
                isVisible = isLive,
                isNear = isNear,
                isPresent = isPresent,
                onExit = { leavers.remove(tile.tileId) },
                stats = state.tileStats(tile, slot.rect),
                onLongPress = { state.eventSink(ElementCallScreenEvent.ToggleTileStats) },
                traversalIndex = if (slot.isSticky) 0f else 1f + (gridIndex[tile.tileId] ?: gridTileIds.size),
                eventSink = state.eventSink,
            )
        }
        state.tiles.forEach { tile ->
            val isComposed = tile.tileId == stickyTileId || tile.tileId in composedGridIds && tile.tileId !in hiddenIds
            if (!isComposed) return@forEach
            key(tile.tileId) { Placed(tile, isPresent = true) }
        }
        leavers.values.forEach { tile -> key(tile.tileId) { Placed(tile, isPresent = false) } }

        val choice = state.spotlight
        if (choice is CallSpotlight.Choice.Hero && spotlightRect != null && heroIds.isNotEmpty()) {
            HeroSpotlight(
                heroIds = heroIds,
                shownId = choice.tileId,
                rect = spotlightRect,
                scrollOffset = scrollOffset,
                isLandscape = metrics.isLandscape,
                tilesById = tilesById,
                state = state,
            )
        }

        // On our own tile, as the design frames draw it: it acts on the picture it sits on, and the
        // bar has one fewer button to fit. A sibling anchored to the slot rather than a child of
        // the tile, so its tap is its own (000 R16). Only with a picture to turn around.
        val own = state.tiles.firstOrNull { it.isLocal && it.tileId in composedGridIds && state.videoFrames[it.tileId] != null }
        val ownSlot = own?.let { lastSlots[it.tileId] }
        if (ownSlot != null) {
            Box(modifier = Modifier.animatedSlot(ownSlot.rect, isSticky = ownSlot.isSticky, scrollOffset = scrollOffset).zIndex(OVERLAY_Z_INDEX)) {
                SwitchCameraButton(
                    onClick = { state.eventSink(ElementCallScreenEvent.SwitchCamera) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                )
            }
        }

        // Anchored to the spotlight *slot* rather than to whoever is in it, so it stays put while
        // people move through the slot underneath it; not drawn without one (open question Q1).
        if (spotlightRect != null) {
            Box(modifier = Modifier.animatedSlot(spotlightRect, isSticky = true, scrollOffset = scrollOffset).zIndex(OVERLAY_Z_INDEX)) {
                MemberCountPill(
                    count = state.memberCount,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(14.dp),
                )
            }
        }
    }
}

private fun ElementCallScreenState.tileStats(tile: CallTileData, slot: Rect): TileStats? {
    if (!isTileStatsVisible) return null
    return TileStats(
        receiveStats = receiveStats[MatrixRtcStreamRef(tile.memberId, tile.streamKind)],
        // A screen is nobody's voice: its owner's audio is read off their camera tile.
        audioStats = if (tile.isScreenShare) null else receiveStats[MatrixRtcStreamRef(tile.memberId, MatrixRtcStreamKind.MICROPHONE)],
        frameEncryption = frameEncryption[tile.memberId],
        requestedWidth = slot.width.roundToInt(),
        requestedHeight = slot.height.roundToInt(),
    )
}

/**
 * Tells the core what this tile's stream should do: its drawn size while on screen (R50, R57),
 * paused while composed off screen (R49: the subscription and the renderer stay, so scrolling it
 * back shows a picture and never an avatar). Released is sent by the stage's linger, after the
 * tile is gone.
 *
 * Keyed on the tile's *target* rectangle rather than its animated one: promotion sends one
 * message when the destination is decided, not sixty as the tile travels there. Nothing for our
 * own tile, whose camera is never subject to constraints (R51), and nothing for a tile with no
 * video.
 */
@Composable
private fun ReportVideoConstraints(
    tile: CallTileData,
    slot: Rect,
    hasVideo: Boolean,
    isLive: Boolean,
    eventSink: (ElementCallScreenEvent) -> Unit,
) {
    val width = slot.width.roundToInt()
    val height = slot.height.roundToInt()
    val currentEventSink by rememberUpdatedState(eventSink)
    LaunchedEffect(tile.tileId, tile.streamKind, hasVideo, isLive, width, height) {
        if (!hasVideo || tile.isLocal) return@LaunchedEffect
        val constraints = if (isLive) {
            if (width <= 0 || height <= 0) return@LaunchedEffect
            MatrixRtcVideoConstraints.live(widthPx = width, heightPx = height)
        } else {
            MatrixRtcVideoConstraints.Paused
        }
        currentEventSink(ElementCallScreenEvent.SetVideoConstraints(tile.memberId, tile.streamKind, constraints))
    }
}

/**
 * Whether a tile's stream is live: true on screen, and kept true until it is half a viewport past
 * the edge, so a tile bouncing at the edge does not flip its stream on every bounce (R58).
 */
@Composable
private fun rememberLive(isVisible: Boolean, isNear: Boolean): Boolean {
    val wasLive = remember { BooleanRef(isVisible) }
    val isLive = isVisible || wasLive.value && isNear
    wasLive.value = isLive
    return isLive
}

/**
 * One tile, at whatever rectangle the arrangement currently gives it.
 *
 * [isPresent] is whether it is still in the call. False means it has left and this is playing it
 * out; [onExit] is what finally removes it. Its video is drawn for as long as it is composed - on
 * screen or paused within the band - so a tile scrolled back shows its last frame, never an avatar.
 */
@Composable
private fun PlacedTile(
    tile: CallTileData,
    videoFrames: Flow<MatrixRtcVideoFrame>?,
    appearance: CallTileAppearance,
    slot: TileSlot,
    scrollOffset: FloatState,
    isVisible: Boolean,
    isNear: Boolean,
    isPresent: Boolean,
    onExit: () -> Unit,
    stats: TileStats?,
    onLongPress: () -> Unit,
    traversalIndex: Float,
    eventSink: (ElementCallScreenEvent) -> Unit,
) {
    // Zero on the first composition, so a tile grows into place rather than being there abruptly -
    // except under inspection, where the animation never runs and starting at zero would mean every
    // preview and screenshot of this screen showed nothing at all.
    val isInspecting = LocalInspectionMode.current
    val presence = remember { Animatable(if (isInspecting) 1f else 0f) }
    val currentOnExit by rememberUpdatedState(onExit)
    LaunchedEffect(isPresent) {
        if (isInspecting) return@LaunchedEffect
        if (isPresent) {
            presence.animateTo(1f, APPEARANCE_SPEC)
        } else {
            presence.animateTo(0f, APPEARANCE_SPEC)
            // Only reached if they stayed gone: coming back cancels this effect at the animation
            // above, so a member who leaves and rejoins mid-fade is never removed underneath
            // themselves.
            currentOnExit()
        }
    }
    val isLive = rememberLive(isVisible, isNear)
    ReportVideoConstraints(tile = tile, slot = slot.rect, hasVideo = videoFrames != null, isLive = isLive, eventSink = eventSink)
    ReportLiveToHooks(tile.tileId, isLive)

    // A member who has left is gone from the frame map in the same breath, and swapping their video
    // for an avatar for the moment they spend fading out reads as a glitch. Their last stream is
    // kept so they fade out on a frozen last frame instead - but only while leaving, because
    // *turning the camera off* has to show the avatar straight away.
    val lastFrames = remember { LastFrames() }
    if (videoFrames != null) lastFrames.value = videoFrames
    val frames = videoFrames ?: lastFrames.value.takeIf { !isPresent }

    CallTile(
        tile = tile,
        videoFrames = frames,
        appearance = appearance,
        stats = stats,
        modifier = Modifier
            .testTag(ElementCallTestTags.tile(tile.tileId))
            .semantics { this.traversalIndex = traversalIndex }
            .animatedSlot(slot.rect, isSticky = slot.isSticky, scrollOffset = scrollOffset)
            // The spotlight draws over the grid passing underneath it (R27); we draw over the rest
            // for the moment a move overlaps.
            .zIndex(
                when {
                    slot.isSticky -> SPOTLIGHT_Z_INDEX
                    tile.isLocal -> LOCAL_Z_INDEX
                    else -> 0f
                }
            )
            .graphicsLayer {
                val progress = presence.value
                alpha = progress
                val scale = ENTER_SCALE + (1f - ENTER_SCALE) * progress
                scaleX = scale
                scaleY = scale
            }
            // A vertical drag that starts on the spotlight does not scroll the grid (R64): a state
            // that reports every delta consumed leaves nothing for the stage's own scrollable.
            .then(if (slot.isSticky) Modifier.scrollable(rememberScrollableState { it }, Orientation.Vertical) else Modifier)
            // Long press for the debug readout: the numbers are about *this* stream, so the gesture
            // that reveals them is on the tile. A drag past the touch slop cancels it and scrolls.
            .combinedClickable(
                onClick = {},
                onLongClick = onLongPress,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ),
    )
}

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
private fun HeroSpotlight(
    heroIds: ImmutableList<String>,
    shownId: String,
    rect: Rect,
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
    Box(
        modifier = Modifier
            .animatedSlot(rect, isSticky = true, scrollOffset = scrollOffset)
            .zIndex(SPOTLIGHT_Z_INDEX)
            .scrollable(rememberScrollableState { it }, Orientation.Vertical)
            .semantics {
                traversalIndex = 0f
                customActions = listOf(
                    CustomAccessibilityAction(nextLabel) { show(shownIndex + 1) },
                    CustomAccessibilityAction(previousLabel) { show(shownIndex - 1) },
                )
            },
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { page -> currentHeroIds.getOrNull(page) ?: page },
            beyondViewportPageCount = 0,
        ) { page ->
            val tile = currentHeroIds.getOrNull(page)?.let { tilesById[it] } ?: return@HorizontalPager
            // The pager may keep a neighbouring page composed for a moment; only the shown hero receives video (R24).
            val isShown = tile.tileId == shownId
            ReportVideoConstraints(tile = tile, slot = rect, hasVideo = state.videoFrames[tile.tileId] != null, isLive = isShown, eventSink = state.eventSink)
            ReportLiveToHooks(tile.tileId, isLive = isShown)
            CallTile(
                tile = tile,
                videoFrames = state.videoFrames[tile.tileId],
                appearance = CallTileAppearance.Spotlight,
                stats = state.tileStats(tile, rect),
                showName = !isLandscape,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(ElementCallTestTags.tile(tile.tileId))
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { state.eventSink(ElementCallScreenEvent.ToggleTileStats) },
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ),
            )
        }
        if (heroIds.size > 1) {
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

/**
 * Small and dark so it reads as part of the tile rather than as another control. The circle is the
 * button's own container: Material's icon button insists on a 48dp touch target and draws it over
 * any smaller size, so the visible circle is the design's and the target is still the accessible one.
 */
@Composable
private fun SwitchCameraButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = ElementCallTheme.colors.overlayScrim,
            contentColor = ElementCallTheme.colors.onOverlay,
        ),
    ) {
        Icon(
            imageVector = ElementCallTheme.icons.switchCamera,
            contentDescription = stringResource(R.string.element_call_a11y_switch_camera),
            modifier = Modifier.size(20.dp),
        )
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

/** How many the core counts in the slot, which is not always how many tiles there are. */
@Composable
private fun MemberCountPill(count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(ElementCallTheme.colors.overlayScrim)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = ElementCallTheme.icons.participants,
            contentDescription = null,
            tint = ElementCallTheme.colors.onOverlay,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = count.toString(),
            style = ElementCallTheme.typography.bodySmMedium,
            color = ElementCallTheme.colors.onOverlay,
        )
    }
}

/**
 * Places the content at [slot], animating there from wherever it was; a first placement starts
 * *at* its slot, so a tile arriving from off screen appears in place (R38). A new target while
 * still moving is headed for from the current position (R39).
 *
 * The animated rectangle is read inside [layout] rather than during composition on purpose: that
 * makes moving a tile a re-layout rather than a recomposition, so nothing inside it - including the
 * `AndroidView` holding a GL renderer - is touched sixty times a second while it travels. The
 * scroll offset is read there too, so a scroll re-places the tiles without re-measuring them.
 *
 * A sticky slot is in viewport coordinates and ignores the offset (contract B9: positioned by it,
 * never animated by it). The offset's share is a spring from 1 to 0 as a tile is promoted into the
 * sticky slot, because a tile leaving a scrolled grid for the spotlight would otherwise jump by
 * the offset on the first frame.
 */
@Composable
private fun Modifier.animatedSlot(
    slot: Rect,
    isSticky: Boolean,
    scrollOffset: FloatState,
): Modifier {
    val bounds = remember { Animatable(slot, Rect.VectorConverter) }
    LaunchedEffect(slot) { bounds.animateTo(slot, SLOT_SPEC) }
    val stickyFactor = remember { Animatable(if (isSticky) 1f else 0f) }
    LaunchedEffect(isSticky) { stickyFactor.animateTo(if (isSticky) 1f else 0f, STICKY_SPEC) }
    return layout { measurable, _ ->
        val current = bounds.value
        val placeable = measurable.measure(
            Constraints.fixed(
                width = current.width.roundToInt().coerceAtLeast(0),
                height = current.height.roundToInt().coerceAtLeast(0),
            )
        )
        layout(placeable.width, placeable.height) {
            val shift = scrollOffset.floatValue * (1f - stickyFactor.value)
            placeable.place(current.left.roundToInt(), (current.top - shift).roundToInt())
        }
    }
}

/** A tile's rectangle, and whether it is sticky (viewport coordinates) or scrolls (content coordinates). */
private data class TileSlot(val rect: Rect, val isSticky: Boolean)

/** Holders that change without recomposing anything. */
private class LayoutRef {
    var value: CallStageLayout? = null
    var viewportHeight: Float = 0f
}

private class StringRef {
    var value: String? = null
}

private class BooleanRef(initial: Boolean) {
    var value: Boolean = initial
}

private class SetRef {
    var value: Set<String> = emptySet()
}

private class TilesRef {
    var value: List<CallTileData> = emptyList()
}

private class LastFrames {
    var value: Flow<MatrixRtcVideoFrame>? = null
}

internal val TILE_SPACING = 8.dp

/**
 * How long a tile stays composed, and its stream subscribed, after leaving the band (R58). The same
 * value as the frame stream's own linger in the call layer, so the SFU release and the decoder
 * release coincide.
 */
internal const val RELEASE_LINGER_MS = 3_000L

/**
 * The ranks whose identities are always in the window: a WORKAROUND for the listen-mode speaker,
 * see the window declaration in [CallStage]. Eight covers seven raised hands, which rank above
 * speakers. Also the window declared while the stage is unmounted (contract B8).
 */
internal val HEAD_RANGE = 0 until 8

private const val ENTER_SCALE = 0.85f

/** Our tile sits over the others, the spotlight over everything passing under it, overlays over every tile. */
private const val LOCAL_Z_INDEX = 1f
private const val SPOTLIGHT_Z_INDEX = 2f
private const val OVERLAY_Z_INDEX = 3f

/**
 * Deliberately not bouncy. A tile carrying someone's face overshooting its position is the kind of
 * flourish that is charming once and irritating for the rest of the call.
 */
private val SLOT_SPEC = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = Rect.VisibilityThreshold,
)

/** [SLOT_SPEC]'s twin for a scalar, so the two motions it is summed with stay one motion. */
private val STICKY_SPEC = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/** Short: this is an acknowledgement that someone arrived, not an event in its own right. */
private val APPEARANCE_SPEC = tween<Float>(durationMillis = 220)
