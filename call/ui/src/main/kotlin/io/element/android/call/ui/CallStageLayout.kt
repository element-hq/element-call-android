/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.ui.geometry.Rect
import kotlin.math.ceil

/**
 * The stage's dimensions and the constants the arrangement is built from, in pixels.
 *
 * Portrait or landscape is the stage's own width against its own height, never the device's
 * orientation, so split screen and a resized window follow (spec 003 R33).
 */
internal data class CallStageMetrics(
    val width: Float,
    val height: Float,
    val gap: Float,
    val margin: Float,
    /** The height the control bar floats over at the bottom of the stage, which the last row scrolls clear of (R44). */
    val controlsClearance: Float,
    val tileAspect: Float = TILE_ASPECT,
    val columns: Int = PORTRAIT_COLUMNS,
    val landscapeColumns: Int = LANDSCAPE_COLUMNS,
    val spotlightAspect: Float = SPOTLIGHT_ASPECT,
) {
    val isLandscape: Boolean get() = width > height

    companion object {
        /** A grid tile is 4 wide by 3 high in every orientation and at every call size (R10). */
        const val TILE_ASPECT = 4f / 3f

        /** The spotlight is 16:9 in portrait, the default presentation ratio, so a typical share fits exactly (R13, R15). */
        const val SPOTLIGHT_ASPECT = 16f / 9f
        const val PORTRAIT_COLUMNS = 2

        /** Four fixed columns, width-driven, as the design frames draw it (R32 as contract B4 sharpens it). */
        const val LANDSCAPE_COLUMNS = 4
    }
}

/**
 * How the stage places its tiles: `(Input) -> CallStageLayout`. One shipped case, [RankedGrid]; a
 * second is an experiment's business (spec 003, non-goal "layout switcher").
 */
internal fun interface CallStageArrangement {
    fun compute(input: CallStageLayout.Input): CallStageLayout

    companion object {
        /** Spec 003: a two-column 4:3 grid scrolling under a sticky 16:9 spotlight. */
        val RankedGrid: CallStageArrangement = CallStageArrangement(::computeRankedGrid)
    }
}

/**
 * Where everything goes, as a value: one rect per grid tile in content coordinates, the spotlight
 * in viewport coordinates because it is sticky (R27), and what the grid scrolls through.
 *
 * Pure so that it can be tested as arithmetic and so that the composed set, the detail window,
 * the constraints and the harness dump all derive from the same rects rather than each from a
 * layout of its own.
 */
internal data class CallStageLayout(
    /** Grid tiles, in content coordinates: `y` grows with the scroll offset. */
    val tiles: Map<String, Rect>,
    /** Each grid tile's row and column, for the harness dump. */
    val gridPositions: Map<String, GridPosition>,
    /** The spotlight slot in viewport coordinates, or null when nothing is spotlit. */
    val spotlight: Rect?,
    val spotlightTileId: String?,
    /** What the grid scrolls through, including the space the control bar floats over (R44). */
    val contentHeight: Float,
    /** Placed nowhere and given no video: the heroes not currently shown (R24). */
    val hiddenTileIds: Set<String>,
    /** The heroes as a stack and which one is shown, or null with none (R19). */
    val heroStack: HeroStack?,
) {
    data class Input(
        /**
         * The tiles the grid places, our own first: the order with every hero and the spotlit tile
         * removed (R1, R17). The presenter does that removal, not the arithmetic.
         */
        val gridTileIds: List<String>,
        val spotlightTileId: String?,
        /** Every hero in the model's order, shown or not (R20). */
        val heroIds: List<String>,
        val metrics: CallStageMetrics,
    )

    data class HeroStack(val count: Int, val shownIndex: Int)

    data class GridPosition(val row: Int, val column: Int)

    fun maxScroll(viewportHeight: Float): Float = (contentHeight - viewportHeight).coerceAtLeast(0f)

    companion object {
        val Empty = CallStageLayout(
            tiles = emptyMap(),
            gridPositions = emptyMap(),
            spotlight = null,
            spotlightTileId = null,
            contentHeight = 0f,
            hiddenTileIds = emptySet(),
            heroStack = null,
        )
    }
}

/**
 * How far a grid tile is from the viewport, which is what its video stream is asked to do (R48,
 * R56, R58): [Live] is drawn and asks for its size, [Paused] is composed off screen and keeps its
 * subscription with the sender stopped, [Released] is beyond the band and let go after a linger.
 */
internal enum class CallTileVisibility {
    Live,
    Paused,
    Released,
    ;

    companion object {
        /** A live tile stays live until it is this many viewports past the edge, so a tile bouncing at the edge does not flicker (R58). */
        const val LIVE_HYSTERESIS = 0.5f

        /** Composed and subscribed within this many viewports beyond the visible area (R48). */
        const val BAND_REACH = 1f

        /**
         * @param rect the tile's rect in content coordinates.
         * @param viewport the visible area in the same coordinates: the stage's size at the scroll offset.
         * @param wasLive whether the tile was live before, which widens the edge it leaves through.
         */
        fun forRect(rect: Rect, viewport: Rect, wasLive: Boolean): CallTileVisibility {
            val liveReach = if (wasLive) viewport.height * LIVE_HYSTERESIS else 0f
            return when {
                rect.overlaps(viewport.inflateVertically(liveReach)) -> Live
                rect.overlaps(viewport.inflateVertically(viewport.height * BAND_REACH)) -> Paused
                else -> Released
            }
        }
    }
}

internal fun Rect.inflateVertically(by: Float) = Rect(left, top - by, right, bottom + by)

/** The grid tiles whose rect overlaps [viewport] extended by [reach] above and below, in content coordinates. */
internal fun CallStageLayout.tilesWithin(viewport: Rect, reach: Float): Set<String> {
    val area = viewport.inflateVertically(reach)
    return tiles.filterValues { it.overlaps(area) }.keys
}

private fun computeRankedGrid(input: CallStageLayout.Input): CallStageLayout {
    val metrics = input.metrics
    if (metrics.width <= 0f || metrics.height <= 0f) return CallStageLayout.Empty
    val spotlightTileId = input.spotlightTileId
    val hidden = input.heroIds.filterNot { it == spotlightTileId }.toSet()
    val heroStack = input.heroIds.takeIf { it.isNotEmpty() }?.let { heroes ->
        CallStageLayout.HeroStack(count = heroes.size, shownIndex = heroes.indexOf(spotlightTileId).coerceAtLeast(0))
    }
    val placed = if (spotlightTileId != null) {
        placeWithSpotlight(input.gridTileIds, metrics)
    } else {
        placeWithoutSpotlight(input.gridTileIds, metrics)
    }
    return CallStageLayout(
        tiles = placed.tiles,
        gridPositions = placed.positions,
        spotlight = placed.spotlight,
        spotlightTileId = spotlightTileId,
        contentHeight = placed.contentHeight,
        hiddenTileIds = hidden,
        heroStack = heroStack,
    )
}

private data class Placement(
    val tiles: Map<String, Rect>,
    val positions: Map<String, CallStageLayout.GridPosition>,
    val spotlight: Rect?,
    val contentHeight: Float,
)

/**
 * Portrait: the spotlight is the full stage width, edge to edge, 16:9, at the top (R13, contract
 * B3); the grid's two columns start under it (R26, R44). Landscape: one tile-wide column on the
 * trailing side showing two whole tiles, and the spotlight takes all the width it leaves, the full
 * stage height (R14, R31). The ordinary grid at any count (R34).
 */
private fun placeWithSpotlight(gridTileIds: List<String>, metrics: CallStageMetrics): Placement {
    val gap = metrics.gap
    val margin = metrics.margin
    return if (metrics.isLandscape) {
        val tileHeight = (metrics.height - 2 * margin - gap) / 2
        val tileWidth = tileHeight * metrics.tileAspect
        val columnLeft = metrics.width - margin - tileWidth
        val spotlight = Rect(0f, 0f, columnLeft - gap, metrics.height)
        val rows = placeRows(gridTileIds, columns = 1, left = columnLeft, top = margin, tileWidth = tileWidth, tileHeight = tileHeight, gap = gap)
        Placement(rows.tiles, rows.positions, spotlight, contentHeight(rows.bottom, metrics))
    } else {
        val spotlight = Rect(0f, 0f, metrics.width, metrics.width / metrics.spotlightAspect)
        val tileWidth = (metrics.width - 2 * margin - (metrics.columns - 1) * gap) / metrics.columns
        val rows = placeRows(
            gridTileIds,
            columns = metrics.columns,
            left = margin,
            top = spotlight.bottom + gap,
            tileWidth = tileWidth,
            tileHeight = tileWidth / metrics.tileAspect,
            gap = gap,
        )
        Placement(rows.tiles, rows.positions, spotlight, contentHeight(rows.bottom, metrics))
    }
}

/**
 * Small calls (R34 to R36, contract B4): alone fills the stage above the controls (002 R18); two share it equally,
 * stacked in portrait and side by side in landscape; three are full-width rows in portrait when
 * they fit above the controls and otherwise the grid, and one row in landscape. Two and three in
 * landscape sit centred on the stage's height. Four or more: the grid, two columns in portrait and
 * four in landscape, width-driven, rows from the top, a partial last row left-aligned (R29 to R32).
 */
private fun placeWithoutSpotlight(gridTileIds: List<String>, metrics: CallStageMetrics): Placement {
    val gap = metrics.gap
    val margin = metrics.margin
    val gridWidth = metrics.width - 2 * margin
    val areaBottom = metrics.height - metrics.controlsClearance
    val areaHeight = areaBottom - margin
    val count = gridTileIds.size
    fun grid(): Placement {
        val columns = if (metrics.isLandscape) metrics.landscapeColumns else metrics.columns
        val tileWidth = (gridWidth - (columns - 1) * gap) / columns
        val rows = placeRows(gridTileIds, columns, left = margin, top = margin, tileWidth = tileWidth, tileHeight = tileWidth / metrics.tileAspect, gap = gap)
        return Placement(rows.tiles, rows.positions, null, contentHeight(rows.bottom, metrics))
    }
    fun centredRow(): Placement {
        var tileWidth = (gridWidth - (count - 1) * gap) / count
        var tileHeight = tileWidth / metrics.tileAspect
        if (tileHeight > metrics.height) {
            tileHeight = metrics.height
            tileWidth = tileHeight * metrics.tileAspect
        }
        val rowWidth = count * tileWidth + (count - 1) * gap
        val left = (metrics.width - rowWidth) / 2
        val top = (metrics.height - tileHeight) / 2
        val rows = placeRows(gridTileIds, columns = count, left = left, top = top, tileWidth = tileWidth, tileHeight = tileHeight, gap = gap)
        return Placement(rows.tiles, rows.positions, null, metrics.height)
    }
    return when {
        count == 0 -> Placement(emptyMap(), emptyMap(), null, metrics.height)
        // Alone: the stage, above the controls, so nothing of ours sits under the bar (R44).
        count == 1 -> Placement(
            mapOf(gridTileIds.single() to Rect(margin, margin, metrics.width - margin, areaBottom)),
            mapOf(gridTileIds.single() to CallStageLayout.GridPosition(0, 0)),
            null,
            metrics.height,
        )
        count == 2 && !metrics.isLandscape -> {
            val tileHeight = (areaHeight - gap) / 2
            val rows = placeRows(gridTileIds, columns = 1, left = margin, top = margin, tileWidth = gridWidth, tileHeight = tileHeight, gap = gap)
            Placement(rows.tiles, rows.positions, null, metrics.height)
        }
        count == 3 && !metrics.isLandscape -> {
            val tileHeight = gridWidth / metrics.tileAspect
            if (3 * tileHeight + 2 * gap <= areaHeight) {
                val rows = placeRows(gridTileIds, columns = 1, left = margin, top = margin, tileWidth = gridWidth, tileHeight = tileHeight, gap = gap)
                Placement(rows.tiles, rows.positions, null, contentHeight(rows.bottom, metrics))
            } else {
                grid()
            }
        }
        count <= 3 && metrics.isLandscape -> centredRow()
        else -> grid()
    }
}

private data class Rows(val tiles: Map<String, Rect>, val positions: Map<String, CallStageLayout.GridPosition>, val bottom: Float)

/** Rows from [top], every tile the same size (R12), a partial last row left-aligned (R29). */
private fun placeRows(
    tileIds: List<String>,
    columns: Int,
    left: Float,
    top: Float,
    tileWidth: Float,
    tileHeight: Float,
    gap: Float,
): Rows {
    val tiles = LinkedHashMap<String, Rect>(tileIds.size)
    val positions = LinkedHashMap<String, CallStageLayout.GridPosition>(tileIds.size)
    tileIds.forEachIndexed { index, tileId ->
        val row = index / columns
        val column = index % columns
        val x = left + column * (tileWidth + gap)
        val y = top + row * (tileHeight + gap)
        tiles[tileId] = Rect(x, y, x + tileWidth, y + tileHeight)
        positions[tileId] = CallStageLayout.GridPosition(row, column)
    }
    val rowCount = ceil(tileIds.size / columns.toFloat()).toInt()
    val bottom = if (rowCount == 0) top else top + rowCount * tileHeight + (rowCount - 1) * gap
    return Rows(tiles, positions, bottom)
}

/** The last row clears the control bar when scrolled to the end (R44); never shorter than the stage. */
private fun contentHeight(lastRowBottom: Float, metrics: CallStageMetrics): Float =
    maxOf(lastRowBottom + metrics.margin + metrics.controlsClearance, metrics.height)
