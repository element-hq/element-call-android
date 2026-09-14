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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.call.api.rtc.MatrixRtcVideoConstraints
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Every member of the call, each drawn once, in a layout that decides where they go rather than in a
 * hierarchy that decides it for them.
 *
 * The obvious structure - a spotlight composable above a grid composable - was what this replaces,
 * and it made the interesting animation impossible: promoting someone means destroying the tile in
 * the grid and creating a different one in the spotlight, so there is nothing to animate *from*, and
 * on the video path it means tearing down a GL renderer and building another one for the same person
 * several times a minute. The three crashes that renderer lifetime produced were all in that churn.
 *
 * Here a member is one keyed composable for as long as they are in the call, and the arrangement is
 * a set of rectangles computed from the member list. Promotion is then a change of rectangle, which
 * animates for free and touches no renderer at all.
 *
 * The one-to-one layout is the same idea taken one step further: it is not a different screen but a
 * different set of rectangles for the same tiles - the other person's over the whole area and ours
 * as a thumbnail in the corner. So a third person joining a DM call, or leaving it, moves everyone
 * to where the other layout puts them without a single renderer being rebuilt.
 *
 * The one exception is deliberate: a member more than a page away in a strip that pages is not
 * composed at all. Their renderer is long gone by then - a tile stops drawing video two seconds
 * after leaving the screen - so what is saved is the shell, which at fifty members is most of the
 * call. They come back as they come within a page, and a speaker promoted from that far arrives at
 * the spotlight as a new tile would rather than sliding in from three screens away.
 *
 * @param pipInsets how far in from the area's edges the one-to-one thumbnail must stay, over and
 * above its own margin - for chrome that floats over the tiles rather than sitting beside them.
 */
@Composable
internal fun CallTileLayout(
    state: NativeCallState,
    modifier: Modifier = Modifier,
    pipInsets: PaddingValues = PaddingValues(0.dp),
) {
    // By tile rather than by member: a member sharing their screen is two tiles, and keying on the
    // member id would make them collide - one composable reused for both, and two collectors on what
    // the video path believes is a single stream.
    val spotlightTileId = state.spotlightParticipant?.tileId
    val layout = state.layout

    // Who is drawn right now: the members of the call, plus anyone who has just left and has not
    // finished fading out. Appended to rather than rebuilt, so a member keeps their position - and
    // therefore their composable, and therefore their renderer - across every other change.
    //
    // Seeded rather than left for the effect below to fill, so that a screenshot test - which
    // renders a composition without ever running one - draws the call rather than an empty screen.
    val rendered = remember { mutableStateListOf<CallParticipant>().apply { addAll(state.tiles) } }
    LaunchedEffect(state.tiles) {
        state.tiles.forEach { participant ->
            val index = rendered.indexOfFirst { it.tileId == participant.tileId }
            // Every snapshot rebuilds the tiles, so most of these are equal values in new objects:
            // writing those would invalidate the list, and everything reading it, for nothing.
            when {
                index < 0 -> rendered.add(participant)
                rendered[index] != participant -> rendered[index] = participant
            }
        }
    }

    // No frame around a one-to-one call: the other person is meant to reach the edges. The 8dp shift
    // when the layout flips happens while every tile is already travelling, so it is not seen.
    val outerPadding = if (layout == CallLayout.OneToOne) 0.dp else TILE_SPACING
    // Clipped so a strip tile scrolled past the edge stops at the edge of the tile area, rather than
    // running on under the chrome.
    BoxWithConstraints(
        modifier = modifier
            .padding(outerPadding)
            .clipToBounds(),
    ) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        // Nothing is drawn into an area with no room in it, rather than everything being drawn at
        // zero size: a tile composed at nothing and then grown would animate in from the corner, and
        // on the video path it would mean building a GL renderer for a tile nobody can see.
        if (width > 0f && height > 0f) {
            val density = LocalDensity.current
            val layoutDirection = LocalLayoutDirection.current
            val spacing = with(density) { TILE_SPACING.toPx() }
            val insets = with(density) {
                EdgeInsets(
                    left = pipInsets.calculateLeftPadding(layoutDirection).toPx(),
                    top = pipInsets.calculateTopPadding().toPx(),
                    right = pipInsets.calculateRightPadding(layoutDirection).toPx(),
                    bottom = pipInsets.calculateBottomPadding().toPx(),
                )
            }

            val pageIndicatorHeight = with(density) { PAGE_INDICATOR_HEIGHT.toPx() }
            val arrangement = remember(state.tiles, spotlightTileId, width, height, spacing, layout, insets, pageIndicatorHeight) {
                computeArrangement(
                    tileIds = state.tiles.map { it.tileId },
                    spotlightTileId = spotlightTileId,
                    width = width,
                    height = height,
                    spacing = spacing,
                    layout = layout,
                    pipInsets = insets,
                    canScroll = true,
                    pageIndicatorHeight = pageIndicatorHeight,
                )
            }
            val slots = arrangement.slots
            val strip = arrangement.strip

            // How far the strip has been swiped, in pixels. One number for every strip tile,
            // subtracted from their rectangles at placement time - see animatedSlot - so a swipe is
            // a re-placement of the tiles and never a recomposition of them.
            val scrollOffset = remember { mutableFloatStateOf(0f) }
            val currentArrangement by rememberUpdatedState(arrangement)
            val scrollable = rememberScrollableState { delta ->
                val max = currentArrangement.strip?.maxScroll ?: 0f
                val target = (scrollOffset.floatValue + delta).coerceIn(0f, max)
                val consumed = target - scrollOffset.floatValue
                scrollOffset.floatValue = target
                // What was actually consumed rather than what was asked for: the difference is
                // what tells a fling it has reached the end.
                consumed
            }
            // A released swipe on a paged strip settles on a page rather than wherever the finger
            // left it; a freely scrolling one flings as any list does.
            val pageFlingVelocity = with(density) { PAGE_FLING_VELOCITY.toPx() }
            val pageFling = rememberSnapFlingBehavior(
                remember { PageSnapLayoutInfoProvider({ currentArrangement.strip?.pages }, scrollOffset, pageFlingVelocity) },
            )
            val stripFling = if (strip?.pages != null) pageFling else null
            // Back to the start when the axis changes - a rotation, or the strip appearing - and
            // deliberately *not* when the strip goes away: the tiles are still animating out of it
            // then, and zeroing the offset under them would make every one of them jump.
            LaunchedEffect(strip?.orientation) {
                if (strip != null) scrollOffset.floatValue = 0f
            }
            // Someone leaving can take the last page with them; ease back to the new last page
            // rather than snap, and a swipe in the meantime takes precedence.
            LaunchedEffect(strip?.maxScroll) {
                val max = strip?.maxScroll ?: return@LaunchedEffect
                val excess = scrollOffset.floatValue - max
                if (excess > 0f) scrollable.animateScrollBy(-excess)
            }
            // Kept after the strip has gone, for the same reason the offset is: the tiles leaving
            // it still need it to animate out of it smoothly.
            val lastStripScroll = remember { LastStripScroll() }
            if (strip != null) {
                lastStripScroll.value = StripScroll(
                    offset = scrollOffset,
                    viewport = strip.viewport,
                    orientation = strip.orientation,
                    fling = stripFling,
                )
            }
            val stripScroll = lastStripScroll.value

            // Which tiles are on screen, as a set that only changes when a tile crosses the edge:
            // scrolling by a pixel recomposes nothing, a tile arriving or leaving recomposes the
            // tiles whose visibility changed.
            val visibleIds by remember(arrangement) {
                derivedStateOf { visibleTiles(arrangement, scrollOffset.floatValue) }
            }
            // Which tiles exist at all: those on screen and within a page of it. A tile further away
            // than that is not composed - not a shell, not an avatar, nothing - because a call of
            // fifty is forty-odd tiles nobody is looking at, and placing every one of them on every
            // frame of a swipe is what would make the swipe stutter. The neighbouring page is kept
            // so that turning to it never shows tiles popping in at the edge.
            val composedIds by remember(arrangement) {
                derivedStateOf { nearbyTiles(arrangement, scrollOffset.floatValue) }
            }
            // A tile parked while it was still drawing has had no chance to say it is off screen -
            // its effects went with it - so it is said here on its behalf. Usually a repeat of what
            // the tile already sent, which the call layer drops.
            val parkedTiles = remember { mutableSetOf<String>() }
            LaunchedEffect(composedIds) {
                val parked = state.tiles.filter { it.tileId !in composedIds && state.videoFrames[it.tileId] != null }
                parked.filter { parkedTiles.add(it.tileId) }.forEach { tile ->
                    state.eventSink(
                        NativeCallEvent.SetVideoConstraints(
                            memberId = tile.memberId,
                            kind = tile.streamKind,
                            constraints = MatrixRtcVideoConstraints.NotVisible,
                        )
                    )
                }
                parkedTiles.retainAll(parked.map { it.tileId }.toSet())
            }

            // Where each member was last placed. A member who has left is out of the arrangement
            // immediately - so the others close over the gap while the leaver is still on screen -
            // and this is what keeps the leaver drawn where they were rather than at the origin.
            // Whether they were in the strip is kept too, so a leaver keeps following the scroll.
            val lastSlots = remember { mutableMapOf<String, TileSlot>() }
            slots.forEach { (tileId, rect) ->
                lastSlots[tileId] = TileSlot(rect = rect, inStrip = strip?.tileIds?.contains(tileId) == true)
            }

            // Catches drags that land on the gaps between strip tiles. Composed before the tiles so
            // it sits under them; a drag on a tile is the tile's own scrollable's business, because
            // pointer events reach the node that was hit and its ancestors, never a sibling below.
            if (strip != null) {
                Box(
                    modifier = Modifier
                        .animatedSlot(strip.viewport)
                        .scrollable(scrollable, strip.orientation, reverseDirection = true, flingBehavior = stripFling),
                )
            }

            rendered.forEach { participant ->
                // A leaver is always composed, wherever they were: their tile is what plays them
                // out, and removes itself when it has. Parked far away it does so in a frame.
                val isComposed = participant.tileId in composedIds || participant.tileId !in slots
                if (!isComposed) return@forEach
                key(participant.tileId) {
                    val tileSlot = lastSlots[participant.tileId] ?: TileSlot(rect = Rect.Zero, inStrip = false)
                    val slot = tileSlot.rect
                    // Drawn while on screen, and for a moment after leaving it, so that scrolling a
                    // tile back and forth over the edge does not build and tear down its renderer
                    // each time. The same answer goes to the SFU, so a fling does not send a burst of
                    // visible/not-visible flips either.
                    val isDrawn = rememberDrawn(isVisible = participant.tileId in visibleIds)
                    // Tell the core how big this really is, so a thumbnail stops receiving the
                    // sender's best layer - or that it is off screen, so it can stop sending at all.
                    // Keyed on the *target* slot rather than the animated one, so promotion sends one
                    // message rather than one per frame of the animation.
                    ReportVideoConstraints(
                        participant = participant,
                        slot = slot,
                        hasVideo = state.videoFrames[participant.tileId] != null,
                        isVisible = isDrawn,
                        eventSink = state.eventSink,
                    )
                    CallTile(
                        participant = participant,
                        videoFrames = state.videoFrames[participant.tileId],
                        isSpotlight = participant.tileId == spotlightTileId,
                        appearance = when {
                            layout != CallLayout.OneToOne -> CallTileAppearance.Card
                            participant.isLocal -> CallTileAppearance.Thumbnail
                            else -> CallTileAppearance.FullBleed
                        },
                        slot = slot,
                        inStrip = tileSlot.inStrip,
                        stripScroll = stripScroll,
                        scrollable = scrollable,
                        isDrawn = isDrawn,
                        isPresent = state.tiles.any { it.tileId == participant.tileId },
                        onExited = { rendered.removeAll { it.tileId == participant.tileId } },
                        stats = if (state.isTileStatsVisible) {
                            TileStats(
                                receiveStats = state.receiveStats[participant.memberId],
                                frameEncryption = state.frameEncryption[participant.memberId],
                                requestedWidth = slot.width.roundToInt(),
                                requestedHeight = slot.height.roundToInt(),
                            )
                        } else {
                            null
                        },
                        onLongPress = { state.eventSink(NativeCallEvent.ToggleTileStats) },
                    )
                }
            }

            // Anchored to the spotlight *slot* rather than to whoever is in it, so it stays put
            // while people move through the slot underneath it. Not in a one-to-one call, where the
            // count is two by definition.
            val spotlightSlot = spotlightTileId?.let { slots[it] }
            if (layout == CallLayout.Group && spotlightSlot != null) {
                Box(modifier = Modifier.animatedSlot(spotlightSlot).zIndex(OVERLAY_Z_INDEX)) {
                    MemberCountPill(
                        count = state.memberCount,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(14.dp),
                    )
                }
            }

            // Which page is showing, under the strip. Anchored like the pill, and only when there
            // is more than one page to be on.
            val pages = strip?.pages
            if (pages != null && pages.count > 1) {
                val currentPage by remember(pages) { derivedStateOf { pages.pageAt(scrollOffset.floatValue) } }
                Box(modifier = Modifier.animatedSlot(pages.indicator).zIndex(OVERLAY_Z_INDEX)) {
                    PageIndicator(
                        pageCount = pages.count,
                        currentPage = currentPage,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }

            // On the thumbnail rather than in the bar, per the design: it acts on the picture it
            // sits on, and the bar has one fewer button to fit. Anchored to the slot for the same
            // reason as the pill, and only when there is a picture to turn around.
            val local = state.tiles.firstOrNull { it.isLocal }
            val localSlot = local?.let { slots[it.tileId] }
            if (layout == CallLayout.OneToOne && localSlot != null && state.videoFrames[local.tileId] != null) {
                Box(modifier = Modifier.animatedSlot(localSlot).zIndex(OVERLAY_Z_INDEX)) {
                    SwitchCameraButton(
                        onClick = { state.eventSink(NativeCallEvent.SwitchCamera) },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp),
                    )
                }
            }
        }
    }
}

/**
 * Small and dark so it reads as part of the thumbnail rather than as a sixth control.
 *
 * The circle is the button's own 40dp container rather than a sized modifier, because Material's
 * icon button insists on a 48dp touch target and draws it over any smaller size it is given. This
 * way the visible circle is the design's and the target is still the accessible one.
 */
@Composable
private fun SwitchCameraButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
        colors = IconButtonDefaults.iconButtonColors(containerColor = PILL_BACKGROUND, contentColor = Color.White),
    ) {
        Icon(
            imageVector = CompoundIcons.SwitchCameraSolid(),
            contentDescription = stringResource(CommonStrings.a11y_switch_camera),
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Tells the core how large this tile actually is, so the SFU can stop sending more than it needs.
 *
 * Keyed on the tile's *target* rectangle rather than its animated one: promotion should send one
 * message when the destination is decided, not sixty as the tile travels there. The size is rounded
 * to whole pixels for the same reason - sub-pixel changes during layout are not news.
 *
 * A tile that is not on screen says so rather than a size: the SFU then stops sending its stream
 * altogether - "no data, instant resume" - which is the whole saving of a strip that scrolls. It
 * stays subscribed, so scrolling it back costs a key frame rather than a renegotiation.
 *
 * Nothing is reported for a tile with no video. There is no stream to constrain, and a member whose
 * camera is off is drawn as an avatar at whatever size the layout likes.
 */
@Composable
private fun ReportVideoConstraints(
    participant: CallParticipant,
    slot: Rect,
    hasVideo: Boolean,
    isVisible: Boolean,
    eventSink: (NativeCallEvent) -> Unit,
) {
    val width = slot.width.roundToInt()
    val height = slot.height.roundToInt()
    LaunchedEffect(participant.tileId, participant.streamKind, hasVideo, isVisible, width, height) {
        if (!hasVideo) return@LaunchedEffect
        val constraints = if (isVisible) {
            if (width <= 0 || height <= 0) return@LaunchedEffect
            MatrixRtcVideoConstraints(isVisible = true, widthPx = width, heightPx = height)
        } else {
            MatrixRtcVideoConstraints.NotVisible
        }
        eventSink(
            NativeCallEvent.SetVideoConstraints(
                memberId = participant.memberId,
                kind = participant.streamKind,
                constraints = constraints,
            )
        )
    }
}

/**
 * Whether a tile should be drawing video: true the moment it is on screen, and for
 * [RENDER_DETACH_MS] after it has left.
 *
 * The delay is what stops a strip scrolled back and forth from building and destroying a GL
 * renderer on every pass, which is the churn this whole file exists to avoid. Once it has elapsed
 * the tile drops its frames, the renderer goes with them, and the stream closes after its own
 * linger - so a tile scrolled away for good stops costing anything within a few seconds.
 */
@Composable
private fun rememberDrawn(isVisible: Boolean): Boolean {
    var isDrawn by remember { mutableStateOf(isVisible) }
    LaunchedEffect(isVisible) {
        if (isVisible) {
            isDrawn = true
        } else {
            delay(RENDER_DETACH_MS)
            isDrawn = false
        }
    }
    return isDrawn
}

/**
 * One member, at whatever rectangle the arrangement currently gives them.
 *
 * @param isPresent whether they are still in the call. False means they have left and this is
 * playing them out; [onExited] is what finally removes them.
 * @param inStrip whether [slot] is in the scrolling strip, and so has the scroll offset taken off it.
 * @param isDrawn whether to draw their video at all. False for a tile scrolled off screen, which
 * shows the avatar instead and lets its renderer go. See [rememberDrawn].
 */
@Composable
private fun CallTile(
    participant: CallParticipant,
    videoFrames: Flow<MatrixRtcVideoFrame>?,
    isSpotlight: Boolean,
    appearance: CallTileAppearance,
    slot: Rect,
    inStrip: Boolean,
    stripScroll: StripScroll?,
    scrollable: ScrollableState,
    isDrawn: Boolean,
    isPresent: Boolean,
    onExited: () -> Unit,
    stats: TileStats?,
    onLongPress: () -> Unit,
) {
    // Zero on the first composition, so a tile grows into place rather than being there abruptly -
    // except under inspection, where the animation never runs and starting at zero would mean every
    // preview and screenshot of this screen showed nothing at all.
    val isInspecting = LocalInspectionMode.current
    val presence = remember { Animatable(if (isInspecting) 1f else 0f) }
    LaunchedEffect(isPresent) {
        if (isInspecting) return@LaunchedEffect
        if (isPresent) {
            presence.animateTo(1f, APPEARANCE_SPEC)
        } else {
            presence.animateTo(0f, APPEARANCE_SPEC)
            // Only reached if they stayed gone: coming back cancels this effect at the animation
            // above, so a member who leaves and rejoins mid-fade is never removed underneath
            // themselves.
            onExited()
        }
    }

    // A member who has left is gone from the frame map in the same breath, and swapping their video
    // for an avatar for the moment they spend fading out reads as a glitch. Their last stream is
    // kept so they fade out on a frozen last frame instead - but only while leaving, because
    // *turning the camera off* has to show the avatar straight away.
    val lastFrames = remember { LastFrames() }
    if (videoFrames != null) lastFrames.value = videoFrames
    // Off screen comes last, after the leaver's frozen frame has been chosen: a tile scrolled away
    // still has to remember what it was showing, or a member who leaves while off screen would come
    // back as an avatar for their fade-out.
    val frames = (videoFrames ?: lastFrames.value.takeIf { !isPresent })?.takeIf { isDrawn }

    CallParticipantTile(
        participant = participant,
        videoFrames = frames,
        isSpotlight = isSpotlight,
        appearance = appearance,
        stats = stats,
        modifier = Modifier
            .animatedSlot(slot, inStrip = inStrip, scroll = stripScroll)
            // We are always drawn on top. The one-to-one thumbnail overlaps the other person, and
            // the tiles are composed in arrival order, so without this whoever joined first would
            // win. Harmless in a grid, where nothing overlaps - and it means that when the other
            // person leaves a one-to-one call they fade out over us rather than under us.
            .zIndex(if (participant.isLocal) LOCAL_Z_INDEX else 0f)
            .graphicsLayer {
                val progress = presence.value
                alpha = progress
                val scale = ENTER_SCALE + (1f - ENTER_SCALE) * progress
                scaleX = scale
                scaleY = scale
            }
            // A drag on a strip tile scrolls the strip. Outside the clickable, as a scrolling list
            // arranges its items: a tap or a long press with no movement still lands on the tile,
            // and a drag past the touch slop cancels it and scrolls instead. Every strip tile shares
            // the one state, so a fling started on one carries on when the finger leaves it.
            .then(
                if (stripScroll != null) {
                    Modifier.scrollable(
                        state = scrollable,
                        orientation = stripScroll.orientation,
                        enabled = inStrip,
                        reverseDirection = true,
                        flingBehavior = stripScroll.fling,
                    )
                } else {
                    Modifier
                }
            )
            // Long press rather than a settings screen, and on the tile rather than anywhere else:
            // the numbers are about *this* stream, so the gesture that reveals them should be too.
            // A plain tap is left free for whatever the layout wants next.
            .combinedClickable(
                onClick = {},
                onLongClick = onLongPress,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ),
    )
}

/**
 * One dot per page, the current one larger and brighter - up to the point where the dots would
 * become a dotted line, after which the page is written out as "3 / 25" instead. A call of fifty
 * on a small phone is twenty-five pages, and twenty-five dots say nothing a number does not.
 */
@Composable
private fun PageIndicator(pageCount: Int, currentPage: Int, modifier: Modifier = Modifier) {
    if (pageCount > MAX_PAGE_DOTS) {
        Text(
            text = "${currentPage + 1} / $pageCount",
            style = ElementTheme.typography.fontBodySmMedium,
            color = Color.White,
            modifier = modifier,
        )
        return
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(pageCount) { page ->
            val isCurrent = page == currentPage
            Box(
                modifier = Modifier
                    .size(if (isCurrent) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(if (isCurrent) Color.White else PAGE_DOT_INACTIVE),
            )
        }
    }
}

/**
 * Settles a released swipe on a page: the next one in the direction of a real fling, the nearest
 * one otherwise - so a page is either turned or not, and a tile never rests half off the edge.
 *
 * No approach phase: the distance is at most a page, and a decay animation followed by a snap
 * reads as two motions where one was wanted.
 */
private class PageSnapLayoutInfoProvider(
    private val pages: () -> StripPages?,
    private val offset: FloatState,
    private val flingVelocity: Float,
) : SnapLayoutInfoProvider {
    override fun calculateApproachOffset(velocity: Float, decayOffset: Float): Float = 0f

    override fun calculateSnapOffset(velocity: Float): Float {
        val pages = pages() ?: return 0f
        val position = offset.floatValue / pages.stride
        val target = when {
            velocity > flingVelocity -> floor(position) + 1f
            velocity < -flingVelocity -> ceil(position) - 1f
            else -> position.roundToInt().toFloat()
        }.coerceIn(0f, (pages.count - 1).toFloat())
        return target * pages.stride - offset.floatValue
    }
}

/** How many the core counts in the slot, which is not always how many tiles there are. */
@Composable
private fun MemberCountPill(count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(PILL_BACKGROUND)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = CompoundIcons.UserProfile(),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = count.toString(),
            style = ElementTheme.typography.fontBodySmMedium,
            color = Color.White,
        )
    }
}

/**
 * Places the content at [slot], animating there from wherever it was.
 *
 * The animated rectangle is read inside [layout] rather than during composition on purpose: that
 * makes moving a tile a re-layout rather than a recomposition, so nothing inside it - including the
 * `AndroidView` holding a GL renderer - is touched sixty times a second while it travels.
 *
 * The strip's scroll offset is read in the placement block alone, so that a swipe re-places the
 * tiles without re-measuring them: their size does not depend on it. It is scaled by how much of a
 * strip tile this currently is - a spring from 1 to 0 as a member is promoted - because a tile
 * leaving a swiped strip for the spotlight would otherwise jump by the offset on the first frame.
 * Two springs of the same spec started together sum to one, so the move stays a single motion.
 *
 * A strip tile is also clipped to the strip's viewport, in the draw phase for the same reason:
 * without it a page swiped past the edge would draw on over the spotlight until it left the whole
 * area. Only while it *is* a strip tile - a tile being promoted travels out unclipped, one being
 * demoted arrives from under the spotlight's edge.
 *
 * @param inStrip whether [slot] is a strip position that the scroll offset applies to.
 * @param scroll the strip's scroll, or null for content that does not scroll.
 */
@Composable
private fun Modifier.animatedSlot(
    slot: Rect,
    inStrip: Boolean = false,
    scroll: StripScroll? = null,
): Modifier {
    // Starts *at* its slot, so a tile appearing does not also fly in from wherever the animation
    // would otherwise have started.
    val bounds = remember { Animatable(slot, Rect.VectorConverter) }
    LaunchedEffect(slot) { bounds.animateTo(slot, SLOT_SPEC) }
    val stripFactor = remember { Animatable(if (inStrip) 1f else 0f) }
    LaunchedEffect(inStrip) { stripFactor.animateTo(if (inStrip) 1f else 0f, STRIP_FACTOR_SPEC) }
    // Where the tile's top-left is right now: its animated rectangle, less the scroll along the
    // strip's axis for as much of a strip tile as it currently is.
    fun position(): Offset {
        val current = bounds.value
        if (scroll == null) return Offset(current.left, current.top)
        val shift = scroll.offset.floatValue * stripFactor.value
        return when (scroll.orientation) {
            Orientation.Horizontal -> Offset(current.left - shift, current.top)
            Orientation.Vertical -> Offset(current.left, current.top - shift)
        }
    }
    return layout { measurable, _ ->
        val current = bounds.value
        val placeable = measurable.measure(
            Constraints.fixed(
                width = current.width.roundToInt().coerceAtLeast(0),
                height = current.height.roundToInt().coerceAtLeast(0),
            )
        )
        layout(placeable.width, placeable.height) {
            val position = position()
            placeable.place(position.x.roundToInt(), position.y.roundToInt())
        }
    }.then(
        if (scroll != null && inStrip) {
            Modifier.drawWithContent {
                val position = position()
                val viewport = scroll.viewport
                clipRect(
                    left = viewport.left - position.x,
                    top = viewport.top - position.y,
                    right = viewport.right - position.x,
                    bottom = viewport.bottom - position.y,
                ) {
                    this@drawWithContent.drawContent()
                }
            }
        } else {
            Modifier
        }
    )
}

/**
 * The strip's scroll, as the tiles read it at placement and draw time, and how a released swipe
 * settles - null for the default fling.
 */
private data class StripScroll(
    val offset: FloatState,
    val viewport: Rect,
    val orientation: Orientation,
    val fling: FlingBehavior?,
)

/** Holds the last strip scroll without making a change to it recompose anything. */
private class LastStripScroll {
    var value: StripScroll? = null
}

/** A tile's last known rectangle, and whether it was a strip position at the time. */
private class TileSlot(val rect: Rect, val inStrip: Boolean)

/**
 * Where everyone goes: the spotlight, and everyone else in a grid beside or below it.
 *
 * Which of those depends on the shape of the area, and it is the same design read twice rather than
 * two designs - held tall the spotlight is across the top with the strip underneath, held wide it is
 * on the left with the strip as a column down the right. Because a rotation only changes the numbers
 * this returns, and `MainActivity` handles the orientation config change itself rather than being
 * recreated, turning the phone animates every tile into its new place with no renderer rebuilt and
 * no frame dropped.
 *
 * In pixels rather than in Dp because it feeds an animation and a `Constraints`, both of which want
 * pixels, and rounding once at the end is better than rounding at every step.
 *
 * @param layout which arrangement is wanted. [CallLayout.OneToOne] only applies when the tiles have
 * the shape it needs - a spotlight and exactly one other - and falls back to the group arrangement
 * otherwise, so callers can pass the state's answer without checking it first.
 * @param pipInsets how far the one-to-one thumbnail keeps from each edge over and above its margin,
 * for chrome that floats over the area. Ignored by the group arrangement, which is never overlapped.
 * @param canScroll whether the strip may page once the grid can no longer hold everyone at full
 * size. False, the default, keeps the grid however small it has to go.
 * @param pageIndicatorHeight room kept under a paged strip for its page dots.
 */
internal fun computeArrangement(
    tileIds: List<String>,
    spotlightTileId: String?,
    width: Float,
    height: Float,
    spacing: Float,
    layout: CallLayout = CallLayout.Group,
    pipInsets: EdgeInsets = EdgeInsets.Zero,
    canScroll: Boolean = false,
    pageIndicatorHeight: Float = 0f,
): TileArrangement {
    val grid = gridSlots(tileIds, spotlightTileId, width, height, spacing, layout, pipInsets)
    val strip = tileIds.filter { it != spotlightTileId }
    // Only ever with a spotlight - a call with nobody else in it has nothing to page - and only
    // with more than one other, which the one-to-one arrangement has already taken.
    if (!canScroll || spotlightTileId == null || strip.size <= 1) {
        return TileArrangement(slots = grid, strip = null)
    }
    // The grid holds everyone for as long as it can do so at the strip's full tile size - two across
    // a phone held tall, one column beside the spotlight held wide. The moment it would have to
    // shrink them to fit, the strip pages instead: the same tiles at the same size, with the ones
    // that do not fit on later pages, where they cost nothing. Better than a wall of faces too
    // small to be worth decoding, which is what shrinking to fit a dozen people came to.
    val scrolling = scrollArrangement(strip, spotlightTileId, width, height, spacing, pageIndicatorHeight)
        ?: return TileArrangement(slots = grid, strip = null)
    val fullWidth = scrolling.slots.getValue(strip.first()).width
    val narrowest = strip.minOf { grid[it]?.width ?: 0f }
    if (narrowest >= fullWidth - SIZE_TOLERANCE) return TileArrangement(slots = grid, strip = null)
    return scrolling
}

/** The rectangles alone. See [computeArrangement]. */
internal fun computeSlots(
    tileIds: List<String>,
    spotlightTileId: String?,
    width: Float,
    height: Float,
    spacing: Float,
    layout: CallLayout = CallLayout.Group,
    pipInsets: EdgeInsets = EdgeInsets.Zero,
): Map<String, Rect> = computeArrangement(tileIds, spotlightTileId, width, height, spacing, layout, pipInsets).slots

/**
 * The spotlight where the grid puts it, with the rest of the call at full size in a strip that
 * overflows off screen rather than shrinking.
 *
 * Held tall the strip is pages of two-across tiles swiped through sideways under the spotlight, as
 * many rows to a page as fit, with dots underneath: Zoom and Teams page a phone this way, and a
 * strip that scrolled freely showed half a row at the edge and left it to the eye to work out where
 * a row ended. Held wide the strip is the column beside the spotlight the grid already gives it,
 * scrolling freely down: a column of one is read as a list, and a list scrolls.
 *
 * The strip's rectangles are where the tiles sit at a scroll of zero, in the same coordinates as
 * everything else, so scrolling is a subtraction at placement time and nothing more. A tile past
 * the viewport's edge is simply not drawn until scrolled to.
 *
 * Null when the area is too small to hold a spotlight and a single row at all, in which case the
 * grid, however cramped, is the better answer.
 *
 * @param indicatorHeight room kept along the bottom of a paged strip for its dots.
 */
private fun scrollArrangement(
    strip: List<String>,
    spotlightTileId: String,
    width: Float,
    height: Float,
    spacing: Float,
    indicatorHeight: Float,
): TileArrangement? {
    val slots = mutableMapOf<String, Rect>()
    if (width > height) {
        // The widest column the grid ever gives the strip, so the switch from grid to scrolling
        // changes nothing about the tiles a two-person strip already had.
        val bandWidth = width * LANDSCAPE_STRIP_MAX_FRACTION
        val viewport = Rect(left = width - bandWidth, top = 0f, right = width, bottom = height)
        slots[spotlightTileId] = Rect(left = 0f, top = 0f, right = viewport.left - spacing, bottom = height)
        val tileHeight = bandWidth / TILE_ASPECT
        if (viewport.height < tileHeight) return null
        strip.forEachIndexed { index, tileId ->
            val top = index * (tileHeight + spacing)
            slots[tileId] = Rect(left = viewport.left, top = top, right = viewport.right, bottom = top + tileHeight)
        }
        return TileArrangement(
            slots = slots,
            strip = ScrollStrip(
                tileIds = strip.toSet(),
                viewport = viewport,
                orientation = Orientation.Vertical,
                contentLength = strip.size * tileHeight + (strip.size - 1) * spacing,
                pages = null,
            ),
        )
    }

    val spotlightHeight = min(width / SPOTLIGHT_ASPECT, height * SPOTLIGHT_MAX_HEIGHT_FRACTION)
    val spotlightWidth = min(width, spotlightHeight * SPOTLIGHT_ASPECT)
    val left = (width - spotlightWidth) / 2f
    slots[spotlightTileId] = Rect(left = left, top = 0f, right = left + spotlightWidth, bottom = spotlightHeight)
    val band = Rect(left = 0f, top = spotlightHeight + spacing, right = width, bottom = height)
    val viewport = Rect(left = band.left, top = band.top, right = band.right, bottom = band.bottom - indicatorHeight)
    val columns = STRIP_COLUMNS
    val tileWidth = (viewport.width - spacing * (columns - 1)) / columns
    val tileHeight = tileWidth / TILE_ASPECT
    if (tileWidth <= 0f || viewport.height < tileHeight) return null
    val rows = ((viewport.height + spacing) / (tileHeight + spacing)).toInt().coerceAtLeast(1)
    val perPage = rows * columns
    val pageCount = ceil(strip.size / perPage.toFloat()).toInt()
    val pageStride = viewport.width + spacing
    // Centred in the viewport as the grid centres its rows, so the switch between the two moves
    // nobody who does not need to move.
    val pageHeight = rows * tileHeight + (rows - 1) * spacing
    val top = viewport.top + (viewport.height - pageHeight) / 2f
    strip.forEachIndexed { index, tileId ->
        val page = index / perPage
        val row = index % perPage / columns
        val column = index % columns
        val tileLeft = viewport.left + page * pageStride + column * (tileWidth + spacing)
        val tileTop = top + row * (tileHeight + spacing)
        slots[tileId] = Rect(left = tileLeft, top = tileTop, right = tileLeft + tileWidth, bottom = tileTop + tileHeight)
    }
    return TileArrangement(
        slots = slots,
        strip = ScrollStrip(
            tileIds = strip.toSet(),
            viewport = viewport,
            orientation = Orientation.Horizontal,
            contentLength = pageCount * pageStride - spacing,
            pages = StripPages(
                count = pageCount,
                stride = pageStride,
                indicator = Rect(left = band.left, top = viewport.bottom, right = band.right, bottom = band.bottom),
            ),
        ),
    )
}

/**
 * Which tiles are on screen at [scrollOffset]: everything outside the strip, and the strip tiles
 * whose scrolled rectangle overlaps the strip's viewport. A tile exactly at the edge is off screen,
 * which is what the next page's tiles are once a swipe has settled.
 */
internal fun visibleTiles(arrangement: TileArrangement, scrollOffset: Float): Set<String> =
    tilesWithin(arrangement, scrollOffset, reach = 0f)

/**
 * Which tiles are on screen or within one page - one viewport, for a strip that scrolls freely -
 * of it at [scrollOffset]. What the layout composes; see `CallTileLayout`.
 */
internal fun nearbyTiles(arrangement: TileArrangement, scrollOffset: Float): Set<String> {
    val strip = arrangement.strip ?: return arrangement.slots.keys
    return tilesWithin(arrangement, scrollOffset, reach = strip.pages?.stride ?: strip.viewportLength)
}

private fun tilesWithin(arrangement: TileArrangement, scrollOffset: Float, reach: Float): Set<String> {
    val strip = arrangement.strip ?: return arrangement.slots.keys
    val window = when (strip.orientation) {
        Orientation.Horizontal -> strip.viewport.inflate(horizontal = reach, vertical = 0f)
        Orientation.Vertical -> strip.viewport.inflate(horizontal = 0f, vertical = reach)
    }
    return arrangement.slots.filter { (tileId, rect) ->
        tileId !in strip.tileIds || strip.scrolled(rect, scrollOffset).overlaps(window)
    }.keys
}

private fun Rect.inflate(horizontal: Float, vertical: Float) =
    Rect(left - horizontal, top - vertical, right + horizontal, bottom + vertical)

/** Where every tile goes, and how the strip scrolls if it does. */
internal data class TileArrangement(
    val slots: Map<String, Rect>,
    /** Null when everyone fits and nothing scrolls. */
    val strip: ScrollStrip?,
)

/**
 * The scrolling strip: which tiles are in it, the window they are seen through, and how far they
 * run past it.
 */
internal data class ScrollStrip(
    val tileIds: Set<String>,
    /** The visible band, in the area's coordinates, less any room kept for the page dots. */
    val viewport: Rect,
    val orientation: Orientation,
    /** From the start of the first tile to the end of the last, along [orientation]. */
    val contentLength: Float,
    /** How the strip pages, or null when it scrolls freely. */
    val pages: StripPages?,
) {
    /** The viewport's extent along [orientation]. */
    val viewportLength: Float
        get() = if (orientation == Orientation.Horizontal) viewport.width else viewport.height

    val maxScroll: Float
        get() = (contentLength - viewportLength).coerceAtLeast(0f)

    /** Where [rect] is drawn once the strip has scrolled by [offset]. */
    fun scrolled(rect: Rect, offset: Float): Rect = when (orientation) {
        Orientation.Horizontal -> rect.translate(-offset, 0f)
        Orientation.Vertical -> rect.translate(0f, -offset)
    }
}

/** A paged strip's pages: how many, how far apart, and where the dots go. */
internal data class StripPages(
    val count: Int,
    /** From the start of one page to the start of the next. */
    val stride: Float,
    /** Where the page dots go, under the viewport. */
    val indicator: Rect,
) {
    /** The page nearest to a scroll of [offset]. */
    fun pageAt(offset: Float): Int = (offset / stride).roundToInt().coerceIn(0, count - 1)
}

/**
 * The spotlight and a grid of everyone else, or the one-to-one pair. See [computeArrangement] for
 * the arrangement callers use.
 */
private fun gridSlots(
    tileIds: List<String>,
    spotlightTileId: String?,
    width: Float,
    height: Float,
    spacing: Float,
    layout: CallLayout,
    pipInsets: EdgeInsets,
): Map<String, Rect> {
    if (width <= 0f || height <= 0f) return emptyMap()
    val slots = mutableMapOf<String, Rect>()
    val strip = tileIds.filter { it != spotlightTileId }
    val area = Rect(0f, 0f, width, height)

    // The other person over everything, us in the corner. The spotlight is already the other person
    // - it is never us, and a shared screen would have made a third tile and a group layout - so no
    // new ids are needed, only a different pair of rectangles for the same two.
    if (layout == CallLayout.OneToOne && spotlightTileId != null && strip.size == 1) {
        slots[spotlightTileId] = area
        slots[strip.single()] = thumbnailSlot(width, height, spacing, pipInsets)
        return slots
    }

    // Nobody else to place: the spotlight has the lot.
    if (spotlightTileId != null && strip.isEmpty()) {
        slots[spotlightTileId] = area
        return slots
    }
    // Nobody spotlighted - which is what being alone in a call looks like, since the spotlight is
    // never ourselves - so the grid has the lot.
    if (spotlightTileId == null) {
        val grid = bestGrid(strip.size, width, height, spacing) ?: return slots
        placeGrid(slots, strip, grid, area, spacing)
        return slots
    }

    val stripArea: Rect
    val grid: Grid
    if (width > height) {
        // The grid is chosen first here, and the spotlight gets everything the grid did not use.
        // Reserving the band first and placing into it afterwards was wrong: the tiles are clamped
        // by the height, so for some counts they came out narrower than the band and the difference
        // was taken off the spotlight and left empty.
        val band = landscapeStrip(strip.size, width, height, spacing)
        grid = bestGrid(strip.size, band.width, height, spacing, band.columns) ?: return slots
        val used = grid.tileWidth * grid.columns + spacing * (grid.columns - 1)
        // Full height and whatever width is left, so it is filled rather than letterboxed. The
        // renderer centre-crops, so a 4:3 camera loses its top and bottom here - which is what the
        // design shows, and better than black bars down both sides of the one tile anybody is
        // actually looking at.
        slots[spotlightTileId] = Rect(left = 0f, top = 0f, right = width - used - spacing, bottom = height)
        stripArea = Rect(left = width - used, top = 0f, right = width, bottom = height)
    } else {
        // Full width unless that would make it too tall to leave room for the strip, in which case
        // it keeps its aspect ratio and centres.
        val spotlightHeight = min(width / SPOTLIGHT_ASPECT, height * SPOTLIGHT_MAX_HEIGHT_FRACTION)
        val spotlightWidth = min(width, spotlightHeight * SPOTLIGHT_ASPECT)
        val left = (width - spotlightWidth) / 2f
        slots[spotlightTileId] = Rect(left = left, top = 0f, right = left + spotlightWidth, bottom = spotlightHeight)
        stripArea = Rect(left = 0f, top = spotlightHeight + spacing, right = width, bottom = height)
        if (stripArea.height <= 0f) return slots
        grid = bestGrid(strip.size, stripArea.width, stripArea.height, spacing) ?: return slots
    }
    placeGrid(slots, strip, grid, stripArea, spacing)
    return slots
}

/**
 * Where our own thumbnail goes in a one-to-one call: the bottom-end corner, sized from the area.
 *
 * Its short side is a fraction of the area's short side, so it is the same proportion of the screen
 * whichever way the phone is held. Its long side follows the area's orientation rather than being
 * fixed portrait: the renderer centre-crops, and a camera held sideways sends a landscape frame, so
 * a portrait thumbnail would crop most of it away just when there is width to spare. Clamped to
 * half the area on either axis for the near-square case, where the long side would otherwise take
 * more of the other person than a thumbnail should.
 */
private fun thumbnailSlot(width: Float, height: Float, spacing: Float, insets: EdgeInsets): Rect {
    val shortSide = min(width, height) * THUMBNAIL_FRACTION
    val longSide = shortSide / THUMBNAIL_ASPECT
    var thumbnailWidth = if (width > height) longSide else shortSide
    var thumbnailHeight = if (width > height) shortSide else longSide
    val scale = minOf(
        1f,
        width * THUMBNAIL_MAX_FRACTION / thumbnailWidth,
        height * THUMBNAIL_MAX_FRACTION / thumbnailHeight,
    )
    thumbnailWidth *= scale
    thumbnailHeight *= scale
    val margin = spacing * THUMBNAIL_MARGIN_SPACINGS
    val right = width - insets.right - margin
    val bottom = height - insets.bottom - margin
    return Rect(left = right - thumbnailWidth, top = bottom - thumbnailHeight, right = right, bottom = bottom)
}

/** Distances in from each edge of the tile area, in pixels. See [CallTileLayout]'s `pipInsets`. */
internal data class EdgeInsets(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    companion object {
        val Zero = EdgeInsets(0f, 0f, 0f, 0f)
    }
}

/** Lays [strip] out into [area] as [grid] describes, centring it there. */
private fun placeGrid(
    slots: MutableMap<String, Rect>,
    strip: List<String>,
    grid: Grid,
    area: Rect,
    spacing: Float,
) {
    val rows = ceil(strip.size / grid.columns.toFloat()).toInt()
    val gridHeight = grid.tileHeight * rows + spacing * (rows - 1)
    val top = area.top + (area.height - gridHeight) / 2f

    strip.forEachIndexed { index, tileId ->
        val row = index / grid.columns
        val column = index % grid.columns
        // The last row is centred against the ones above rather than left-aligned under them, which
        // is what stops an odd number of members looking like a mistake.
        val itemsInRow = min(grid.columns, strip.size - row * grid.columns)
        val rowWidth = grid.tileWidth * itemsInRow + spacing * (itemsInRow - 1)
        val left = area.left + (area.width - rowWidth) / 2f + column * (grid.tileWidth + spacing)
        val tileTop = top + row * (grid.tileHeight + spacing)
        slots[tileId] = Rect(
            left = left,
            top = tileTop,
            right = left + grid.tileWidth,
            bottom = tileTop + grid.tileHeight,
        )
    }
}

/**
 * The band down the right-hand side in landscape: how wide it is, and whether it is a single column.
 *
 * Its width is derived from the tiles rather than fixed, so a call with two other people in it gets
 * a column of two big tiles and one with five gets a column of five smaller ones, instead of both
 * getting the same band with different amounts of empty space in it. Capped, because one other
 * person would otherwise take a third of the screen for their tile alone.
 *
 * Below the floor the single column stops being the right answer - stacking that many people makes
 * tiles too thin to recognise anyone in - so the column is given up and the band is handed to the
 * usual grid search instead. That is the design degrading rather than the design breaking: it holds
 * for the number of people a call like this actually has, and stays legible past it.
 */
private fun landscapeStrip(count: Int, width: Float, height: Float, spacing: Float): LandscapeStrip {
    val stackedTileWidth = (height - spacing * (count - 1)) / count * TILE_ASPECT
    val floor = width * LANDSCAPE_STRIP_MIN_FRACTION
    return if (stackedTileWidth < floor) {
        LandscapeStrip(width = floor, columns = null)
    } else {
        LandscapeStrip(width = min(stackedTileWidth, width * LANDSCAPE_STRIP_MAX_FRACTION), columns = 1)
    }
}

private class LandscapeStrip(val width: Float, val columns: Int?)

private class Grid(val columns: Int, val tileWidth: Float, val tileHeight: Float)

/**
 * The column count that makes the tiles largest while still fitting everyone.
 *
 * Searched rather than fixed at two because the strip should hold whoever is in the call without
 * scrolling for as long as it can: a fixed count either wastes the width on a small call or runs
 * off the bottom on a larger one. Once it would have to shrink the tiles to fit, the search is
 * abandoned for the scrolling strip instead - see [computeArrangement].
 */
private fun bestGrid(count: Int, width: Float, height: Float, spacing: Float, forcedColumns: Int? = null): Grid? {
    var best: Grid? = null
    for (columns in forcedColumns?.let { it..it } ?: 1..count) {
        val rows = ceil(count / columns.toFloat()).toInt()
        var tileWidth = (width - spacing * (columns - 1)) / columns
        var tileHeight = tileWidth / TILE_ASPECT
        if (tileHeight * rows + spacing * (rows - 1) > height) {
            tileHeight = (height - spacing * (rows - 1)) / rows
            tileWidth = tileHeight * TILE_ASPECT
        }
        if (tileWidth <= 0f || tileHeight <= 0f) continue
        if (best == null || tileWidth > best.tileWidth) {
            best = Grid(columns, tileWidth, tileHeight)
        }
    }
    return best
}

/** Holds the last stream a member had, without making a change to it recompose anything. */
private class LastFrames {
    var value: Flow<MatrixRtcVideoFrame>? = null
}

private val TILE_SPACING = 8.dp
private const val SPOTLIGHT_ASPECT = 4f / 3f
private const val TILE_ASPECT = 4f / 3f
private const val SPOTLIGHT_MAX_HEIGHT_FRACTION = 0.6f

/**
 * How many tiles across a page is when the phone is held tall. Two, per the design: a size-based
 * threshold let the grid pack three columns and four rows onto a phone before giving up, and a
 * call of a dozen people was a wall of faces too small to be worth decoding.
 */
private const val STRIP_COLUMNS = 2

/** Slack for comparing tile widths the grid and the strip computed by different routes. */
private const val SIZE_TOLERANCE = 0.5f

/** Room under a paged strip for its dots. */
private val PAGE_INDICATOR_HEIGHT = 20.dp
private val PAGE_DOT_INACTIVE = Color(0x66FFFFFF)

/** More pages than this are counted rather than dotted. Eight dots is about as many as an eye counts. */
private const val MAX_PAGE_DOTS = 8

/** How fast a released swipe has to be going, per second, to turn the page rather than settle on the nearest. */
private val PAGE_FLING_VELOCITY = 150.dp

/** How long a tile keeps drawing after leaving the screen. See [rememberDrawn]. */
private const val RENDER_DETACH_MS = 2_000L

/** Bounds on the landscape strip column, as fractions of the width. See [landscapeStripWidth]. */
private const val LANDSCAPE_STRIP_MIN_FRACTION = 0.14f
private const val LANDSCAPE_STRIP_MAX_FRACTION = 0.28f

/** The one-to-one thumbnail: short side over long side, and the short side as a share of the area's. */
private const val THUMBNAIL_ASPECT = 2f / 3f
private const val THUMBNAIL_FRACTION = 0.38f

/** Neither side of the thumbnail may take more than this of the matching side of the area. */
private const val THUMBNAIL_MAX_FRACTION = 0.5f

/** The thumbnail's margin from the area's edge, in tile spacings: 16dp. */
private const val THUMBNAIL_MARGIN_SPACINGS = 2

private const val ENTER_SCALE = 0.85f

/** Our tile sits over the others; overlays anchored to slots sit over every tile. */
private const val LOCAL_Z_INDEX = 1f
private const val OVERLAY_Z_INDEX = 2f

/** Fixed rather than themed: it sits over video, which is not a themed surface. */
private val PILL_BACKGROUND = Color(0xCC15191E)

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
private val STRIP_FACTOR_SPEC = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/** Short: this is an acknowledgement that someone arrived, not an event in its own right. */
private val APPEARANCE_SPEC = tween<Float>(durationMillis = 220)
