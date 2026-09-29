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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
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
    /**
     * How much of the stage's top the top bar floats over. The grid is arranged below it, while a
     * fullscreen tile takes the stage from [fullscreenTop]. The stage keeps its bounds whether or
     * not a tile fills it, so nothing already drawn moves when one starts to (000 R7).
     */
    topClearance: Dp,
    /** Where a fullscreen tile starts, keeping the status bar clear (000 R1). */
    fullscreenTop: Dp,
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
            // Not while a tile fills the stage: a drag there pans the picture (000 R23).
            .scrollable(scrollable, Orientation.Vertical, reverseDirection = true, enabled = state.fullscreenTileId == null),
    ) {
        val density = LocalDensity.current
        val gridTop = with(density) { topClearance.toPx() }
        // From here on, coordinates are those of the area under the top bar, which is what the
        // arrangement divides up; only a fullscreen tile reaches above it.
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat() - gridTop
        // Nothing is drawn into an area with no room in it: a tile composed at nothing and then
        // grown would animate in from the corner, and would build a GL renderer nobody can see.
        if (width <= 0f || height <= 0f) return@BoxWithConstraints
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
        // Fullscreen is a placement over the arrangement, not an arrangement: the grid's rects and
        // its scroll offset are untouched, so leaving returns to the position it had (003 R63, 000 R26).
        val fullscreenId = state.fullscreenTileId
        val fullscreenRect = Rect(0f, with(density) { fullscreenTop.toPx() } - gridTop, width, height)
        val gridTileIds = remember(state.tiles, spotlightTileId) { state.gridTiles.map { it.tileId } }
        val heroIds = remember(state.tiles) { state.heroes }
        val layout = remember(gridTileIds, spotlightTileId, heroIds, metrics, arrangement) {
            arrangement.compute(CallStageLayout.Input(gridTileIds, spotlightTileId, heroIds, metrics))
        }
        currentLayout.value = layout
        currentLayout.viewportHeight = height

        // Someone leaving can shorten the grid past the offset; ease back to the new end rather than
        // showing an empty area below the last row (R42). A swipe in the meantime takes precedence.
        LaunchedEffect(layout.contentHeight, fullscreenId) {
            // Not while fullscreen: the grid waits where it was left for the way out (R63).
            if (fullscreenId != null) return@LaunchedEffect
            val excess = scrollOffset.floatValue - layout.maxScroll(height)
            if (excess > 0f) scrollable.animateScrollBy(-excess)
        }

        // The composed band, as a set that only changes when a tile crosses its edge, so scrolling by
        // a pixel recomposes nothing here. Whether a composed tile is on screen is the tile's own
        // question (see Placed): a tile crossing the viewport's edge recomposes itself and nothing
        // else, where a stage-wide set would recompose the whole stage at every edge a fling crosses.
        val viewport = { Rect(0f, scrollOffset.floatValue, width, scrollOffset.floatValue + height) }
        // While a tile fills the stage nobody is looking at the others: they leave the band, are
        // paused at once and released after the linger, and come straight back on the way out (000 R17, R18).
        val bandIds by remember(layout, fullscreenId) {
            derivedStateOf { if (fullscreenId != null) emptySet() else layout.tilesWithin(viewport(), reach = height * CallTileVisibility.BAND_REACH) }
        }

        // After the stage flips between portrait and landscape, the grid tile that was first visible
        // before it is still visible (R66): the offset is re-seated on its row rather than reset. The
        // tile is found in the arrangement being left, at the offset of that moment, read without
        // observing it so a scroll does not recompose the stage.
        val firstVisibleId = remember { StringRef() }
        val previous = remember { PreviousLayout() }
        val isLandscape = metrics.isLandscape
        val previousLayout = previous.layout
        if (previousLayout != null && previous.isLandscape != isLandscape) {
            val offset = Snapshot.withoutReadObservation { scrollOffset.floatValue }
            val seen = Rect(0f, offset, previous.width, offset + previous.height)
            firstVisibleId.value = previousLayout.tiles.filterValues { it.overlaps(seen) }.minByOrNull { it.value.top }?.key
        }
        previous.layout = layout
        previous.width = width
        previous.height = height
        previous.isLandscape = isLandscape
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
        LaunchedEffect(bandIds, spotlightTileId, fullscreenId) {
            fun constrain(id: String, constraints: MatrixRtcVideoConstraints) {
                val tile = tilesById[id] ?: return
                if (!tile.isLocal && tile.hasVideo) {
                    currentEventSink(ElementCallScreenEvent.SetVideoConstraints(tile.memberId, tile.streamKind, constraints))
                }
            }
            val back = bandIds - lastBand.value + setOfNotNull(spotlightTileId, fullscreenId)
            back.forEach { id ->
                lingerJobs.remove(id)?.cancel()
                retainedIds.remove(id)
            }
            // A hero no longer shown is hidden by the arrangement, so it is outside the band and
            // released after the linger like any other (R24); its page is gone, so it is paused here.
            // Only the hero that was shown: one that arrived unshown was never subscribed.
            val unshown = lastShown.value?.takeIf { it != spotlightTileId && it !in bandIds }
            unshown?.let { constrain(it, MatrixRtcVideoConstraints.Paused) }
            val gone = lastBand.value - bandIds - setOfNotNull(spotlightTileId, fullscreenId) + setOfNotNull(unshown)
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
        LaunchedEffect(composedGridIds, bandIds, spotlightTileId, fullscreenId, state.tiles) {
            val composed = state.tiles.filter { it.tileId in composedGridIds || it.tileId == spotlightTileId || it.tileId == fullscreenId }
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
            val drawnOutOfRank = composed.filter { it.tileId == spotlightTileId || it.tileId == fullscreenId }.map { it.id }
            val also = (drawnOutOfRank + head).toSet()
            currentEventSink(ElementCallScreenEvent.SetDetailWindow(MatrixRtcDetailWindow(ranks = range, also = also)))
        }

        // Where each tile was last placed, so a leaver keeps fading where it was while the others
        // close over the gap (the arrangement drops them at once).
        val lastSlots = remember { mutableMapOf<String, TileSlot>() }
        layout.tiles.forEach { (tileId, rect) -> lastSlots[tileId] = TileSlot(rect, isSticky = false) }
        val spotlightRect = layout.spotlight
        if (stickyTileId != null && spotlightRect != null) lastSlots[stickyTileId] = TileSlot(spotlightRect, isSticky = true)
        if (fullscreenId != null && fullscreenId !in heroIds) lastSlots[fullscreenId] = TileSlot(fullscreenRect, isSticky = true, isFullscreen = true)

        // Who has just left and has not finished fading out. Their tile plays them out and removes
        // itself when it has; parked beyond the band it does so in a frame.
        val leavers = remember { mutableStateMapOf<String, CallTileData>() }
        val lastTiles = remember { TilesRef() }
        // Every tile the stage has seen in the call. A tile composed for the first time because it
        // scrolled into reach is already in the call and appears in place, at full opacity; only a
        // tile that has just joined fades in (R38, R49: never a blank while scrolling).
        val knownIds = remember { state.tiles.mapTo(mutableSetOf()) { it.tileId } }
        LaunchedEffect(state.tiles) {
            state.tiles.mapTo(knownIds) { it.tileId }
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
                hooks.fullscreenTileId = fullscreenId
                hooks.heroes = heroIds
                hooks.eventSink = state.eventSink
                hooks.scrollTo = { target -> scrollOffset.floatValue = target.coerceIn(0f, layout.maxScroll(height)) }
                hooks.animateScrollTo = { target ->
                    lingerScope.launch { scrollable.animateScrollBy(target.coerceIn(0f, layout.maxScroll(height)) - scrollOffset.floatValue) }
                }
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
            // The sticky speaker is live unless something else fills the stage (000 R17); a grid
            // tile while its rect overlaps the viewport, and near while within half a viewport of it.
            val isVisible = remember(slot, fullscreenId, width, height) {
                derivedStateOf {
                    when {
                        slot.isSticky -> fullscreenId == null || slot.isFullscreen
                        fullscreenId != null -> false
                        else -> slot.rect.overlaps(viewport())
                    }
                }
            }
            val isNear = remember(slot, fullscreenId, width, height) {
                derivedStateOf {
                    when {
                        slot.isSticky -> true
                        fullscreenId != null -> false
                        else -> slot.rect.overlaps(viewport().inflateVertically(height * CallTileVisibility.LIVE_HYSTERESIS))
                    }
                }
            }
            PlacedTile(
                tile = tile,
                videoFrames = state.videoFrames[tile.tileId],
                appearance = when {
                    slot.isFullscreen -> CallTileAppearance.Fullscreen
                    slot.isSticky -> CallTileAppearance.Spotlight
                    else -> CallTileAppearance.Grid
                },
                slot = slot,
                scrollOffset = scrollOffset,
                isVisible = isVisible,
                isNear = isNear,
                isPresent = isPresent,
                onExit = { leavers.remove(tile.tileId) },
                isArrival = tile.tileId !in knownIds,
                stats = state.tileStats(tile, slot.rect),
                onLongPress = { state.eventSink(ElementCallScreenEvent.ToggleTileStats) },
                traversalIndex = if (slot.isSticky) 0f else 1f + (gridIndex[tile.tileId] ?: gridTileIds.size),
                eventSink = state.eventSink,
            )
        }
        // The whole grid moves as one layer: a scroll changes this translation and nothing else, so no
        // tile is re-placed or recomposed for it. Sticky slots counter-translate in their own
        // placement (animatedSlot), which is the only placement a scroll reaches.
        Box(modifier = Modifier.fillMaxSize().graphicsLayer { translationY = gridTop - scrollOffset.floatValue }) {
        state.tiles.forEach { tile ->
            val isComposed = tile.tileId == stickyTileId ||
                tile.tileId == fullscreenId && tile.tileId !in heroIds ||
                tile.tileId in composedGridIds && tile.tileId !in hiddenIds
            if (!isComposed) return@forEach
            key(tile.tileId) { Placed(tile, isPresent = true) }
        }
        leavers.values.forEach { tile -> key(tile.tileId) { Placed(tile, isPresent = false) } }

        val choice = state.spotlight
        if (choice is CallSpotlight.Choice.Hero && spotlightRect != null && heroIds.isNotEmpty()) {
            // Double-tapping the spotlight fullscreens the hero currently shown (003 R62): the pager's
            // box itself takes the stage, so the page keeps its renderer (000 R7, R20).
            val isFullscreen = fullscreenId == choice.tileId
            HeroSpotlight(
                heroIds = heroIds,
                shownId = choice.tileId,
                rect = if (isFullscreen) fullscreenRect else spotlightRect,
                isFullscreen = isFullscreen,
                isDimmed = fullscreenId != null && !isFullscreen,
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
        if (ownSlot != null && fullscreenId == null) {
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
        if (spotlightRect != null && fullscreenId == null) {
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
}

internal fun ElementCallScreenState.tileStats(tile: CallTileData, slot: Rect): TileStats? {
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
internal fun ReportVideoConstraints(
    tile: CallTileData,
    slot: Rect,
    hasVideo: Boolean,
    isLive: Boolean,
    eventSink: (ElementCallScreenEvent) -> Unit,
    /** The zoom, quantised to a power of two: simulcast layers are that far apart, and a pinch must not walk dozens of sizes through the FFI (000 R13). */
    scaleFactor: Int = 1,
) {
    val width = (slot.width * scaleFactor).roundToInt()
    val height = (slot.height * scaleFactor).roundToInt()
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
    isVisible: State<Boolean>,
    isNear: State<Boolean>,
    isPresent: Boolean,
    onExit: () -> Unit,
    /** Whether this tile has just joined the call, rather than scrolled into reach: only an arrival fades in. */
    isArrival: Boolean,
    stats: TileStats?,
    onLongPress: () -> Unit,
    traversalIndex: Float,
    eventSink: (ElementCallScreenEvent) -> Unit,
) {
    // Zero on the first composition of a member who has just joined, so they grow into place rather
    // than being there abruptly. Not under inspection, where the animation never runs.
    val isInspecting = LocalInspectionMode.current
    val presence = remember { Animatable(if (isInspecting || !isArrival) 1f else 0f) }
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
    val isLive = rememberLive(isVisible.value, isNear.value)
    // In fullscreen only: pinch from fitted up to 4x, drag the zoomed picture within its edges, and
    // both reset when fullscreen ends (000 R14, R22 to R24). Under fitted the scale clamps to fitted
    // and never leaves fullscreen.
    var zoom by remember(slot.isFullscreen) { mutableStateOf(VideoTransform.None) }
    val transformable = rememberTransformableState { _, zoomChange, pan, _ ->
        val scale = (zoom.scale * zoomChange).coerceIn(1f, MAX_ZOOM)
        val maxX = (slot.rect.width * scale - slot.rect.width) / 2
        val maxY = (slot.rect.height * scale - slot.rect.height) / 2
        zoom = VideoTransform(
            scale = scale,
            offset = Offset((zoom.offset.x + pan.x).coerceIn(-maxX, maxX), (zoom.offset.y + pan.y).coerceIn(-maxY, maxY)),
        )
    }
    ReportVideoConstraints(
        tile = tile,
        slot = slot.rect,
        hasVideo = videoFrames != null,
        isLive = isLive,
        eventSink = eventSink,
        scaleFactor = if (slot.isFullscreen) zoom.scale.quantisedToPowerOfTwo() else 1,
    )
    ReportLiveToHooks(tile.tileId, isLive)
    // Above every other tile for the whole of the move, both ways: raised the moment fullscreen is
    // entered, and lowered only once the way back has landed, never at its start (000 R7).
    var isRaised by remember { mutableStateOf(slot.isFullscreen) }
    if (slot.isFullscreen) isRaised = true
    // The change from cropped to fitted is travelled across the move, never applied at either end (000 R7).
    val fit by animateFloatAsState(targetValue = appearance.fitFor(tile), animationSpec = FIT_SPEC, label = "tileFit")
    val fullscreenLabel = stringResource(if (slot.isFullscreen) R.string.element_call_a11y_exit_fullscreen else R.string.element_call_a11y_enter_fullscreen)

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
        fit = fit,
        // The HUD names the fullscreen tile (000 R11); the tile's own pill would double it.
        showName = !slot.isFullscreen,
        videoTransform = if (slot.isFullscreen) zoom else VideoTransform.None,
        modifier = Modifier
            .testTag(ElementCallTestTags.tile(tile.tileId))
            .semantics {
                this.traversalIndex = traversalIndex
                // Double tap is how TalkBack activates anything, so the gesture cannot reach it (000 R21).
                customActions = listOf(CustomAccessibilityAction(fullscreenLabel) {
                    eventSink(ElementCallScreenEvent.ToggleFullscreen(tile.tileId))
                    true
                })
            }
            .animatedSlot(slot.rect, isSticky = slot.isSticky, scrollOffset = scrollOffset, onArrive = { isRaised = slot.isFullscreen })
            // The spotlight draws over the grid passing underneath it (R27); we draw over the rest
            // for the moment a move overlaps; a tile filling the stage is above everything for the
            // whole of its move, including the tiles on their way out (000 R7).
            .zIndex(
                when {
                    isRaised -> FULLSCREEN_Z_INDEX
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
            .then(if (slot.isFullscreen) Modifier.transformable(transformable) else Modifier)
            // One pointer node per tile: a double tap enters and leaves fullscreen (000 R1, R2, R4), a
            // single tap toggles the HUD there (000 R9), a long press the debug readout. A drag past
            // the touch slop cancels a tap (003 R65); a down during a fling stops it without consuming
            // it, so a double tap right after a scroll is two clean taps.
            .combinedClickable(
                onClick = { if (slot.isFullscreen) eventSink(ElementCallScreenEvent.ToggleFullscreenChrome) },
                onDoubleClick = { eventSink(ElementCallScreenEvent.ToggleFullscreen(tile.tileId)) },
                onLongClick = onLongPress,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ),
    )
}

internal fun Float.quantisedToPowerOfTwo(): Int = when {
    this >= QUAD_ZOOM -> 4
    this >= DOUBLE_ZOOM -> 2
    else -> 1
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
internal fun Modifier.animatedSlot(
    slot: Rect,
    isSticky: Boolean,
    scrollOffset: FloatState,
    /** Told when the move to [slot] has finished; not told when a newer slot cancelled it. */
    onArrive: (() -> Unit)? = null,
): Modifier {
    val bounds = remember { Animatable(slot, Rect.VectorConverter) }
    val currentOnArrived by rememberUpdatedState(onArrive)
    LaunchedEffect(slot) {
        bounds.animateTo(slot, SLOT_SPEC)
        currentOnArrived?.invoke()
    }
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
            // The grid's layer is translated by the offset; a sticky slot undoes it. A grid tile at
            // rest does not read the offset at all, so a scroll does not re-place it.
            val factor = stickyFactor.value
            val shift = if (factor == 0f) 0f else scrollOffset.floatValue * factor
            placeable.place(current.left.roundToInt(), (current.top + shift).roundToInt())
        }
    }
}

/** A tile's rectangle, and whether it is sticky (viewport coordinates) or scrolls (content coordinates). */
private data class TileSlot(val rect: Rect, val isSticky: Boolean, val isFullscreen: Boolean = false)

/** Holders that change without recomposing anything. */
private class LayoutRef {
    var value: CallStageLayout? = null
    var viewportHeight: Float = 0f
}

private class PreviousLayout {
    var layout: CallStageLayout? = null
    var width: Float = 0f
    var height: Float = 0f
    var isLandscape: Boolean = false
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

/** Our tile sits over the others, the spotlight over everything passing under it, overlays over every tile, a fullscreen tile over all. */
private const val LOCAL_Z_INDEX = 1f
internal const val SPOTLIGHT_Z_INDEX = 2f
private const val OVERLAY_Z_INDEX = 3f
internal const val FULLSCREEN_Z_INDEX = 4f

/** The zoom runs from fitted up to 4x (000 R22); constraints step at the simulcast layers' powers of two (000 R13). */
internal const val MAX_ZOOM = 4f
private const val DOUBLE_ZOOM = 2f
private const val QUAD_ZOOM = 4f

/**
 * Deliberately not bouncy. A tile carrying someone's face overshooting its position is the kind of
 * flourish that is charming once and irritating for the rest of the call.
 */
private val SLOT_SPEC = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = Rect.VisibilityThreshold,
)

/** The fill-to-fit continuum moves with the slot, so the aspect is right at every point of the move (000 R7). */
internal val FIT_SPEC = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/**
 * [SLOT_SPEC]'s twin for a scalar, so the two motions it is summed with stay one motion.
 *
 * The factor multiplies the scroll offset, so where a spring stops and snaps is a distance on
 * screen: the default 0.01 is a 30 px jump at the end of the move for a grid scrolled 3000 px.
 * This threshold keeps the snap under half a pixel for offsets up to 50 000 px.
 */
private val STICKY_SPEC = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = 1f / 100_000,
)

/** Short: this is an acknowledgement that someone arrived, not an event in its own right. */
private val APPEARANCE_SPEC = tween<Float>(durationMillis = 220)
