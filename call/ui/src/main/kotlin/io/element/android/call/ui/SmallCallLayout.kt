/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.ceil
import kotlin.math.min

/** Where our floating tile sits. Physical: a right-to-left locale does not mirror it (019 R18). */
internal enum class OwnTileCorner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    ;

    val isLeft: Boolean get() = this == TOP_LEFT || this == BOTTOM_LEFT
    val isTop: Boolean get() = this == TOP_LEFT || this == TOP_RIGHT

    companion object {
        /** Where every call starts (019 R18). */
        val Initial = BOTTOM_RIGHT

        fun of(isLeft: Boolean, isTop: Boolean) = when {
            isTop && isLeft -> TOP_LEFT
            isTop -> TOP_RIGHT
            isLeft -> BOTTOM_LEFT
            else -> BOTTOM_RIGHT
        }
    }
}

/** Our own tile as the small layout needs it: whether it floats, and what shape it takes. */
internal data class OwnTileInput(
    val tileId: String,
    val hasVideo: Boolean,
    /** Width over height of our picture as last drawn, or null before the first frame. */
    val videoAspect: Float?,
    val corner: OwnTileCorner,
)

/** Our own tile while it floats (019 R2): its rect in viewport coordinates and the corner it is in. */
internal data class FloatingOwnTile(val tileId: String, val rect: Rect, val corner: OwnTileCorner)

/**
 * The layout of a call of at most five tiles, ours included (spec 019): a layout of its own, not a
 * case of the ranked grid. Nothing scrolls; tiles are placed in the order given, which is the core's
 * join order once [RANKING_THRESHOLD] is set (R7).
 */
internal object SmallCallLayout {
    /**
     * Every remote tile this layout can hold: at or below it the core publishes join order, the
     * same on every device, which is the arrival order the layout places by (019 R7).
     */
    const val RANKING_THRESHOLD = 4

    /** The most tiles, ours included, the layout holds; the next one switches to the grid (R1). */
    const val MAX_TILES = 5

    /** Our floating tile's sides, in dp: 100 x 150 upright, 150 x 100 sideways, 100 x 100 with the camera off (R17, R28). */
    const val FLOATING_SHORT_SIDE = 100f
    const val FLOATING_LONG_SIDE = 150f

    /** Landscape rows hold at most this many (R16). */
    private const val LANDSCAPE_ROW = 4

    /** A flick is projected over about half a second, a scroll's normal deceleration (contract C5). */
    private const val DECELERATION_RATE = 0.998f

    /**
     * Whether this layout applies (R1, R8): no remote screen share, and at most [MAX_TILES] person tiles.
     * Keyed on the share, not on a hero: R8 is about shares. No margin and no memory (R33).
     */
    fun applies(tiles: List<CallTileData>): Boolean =
        tiles.none { it.isScreenShare && !it.isLocal } && tiles.count { !it.isScreenShare } <= MAX_TILES

    /**
     * @param input the tiles in order, ours first when we are in the call. [CallStageLayout.Input.own]
     * says which is ours; without it every tile is placed as a remote one.
     */
    fun compute(input: CallStageLayout.Input): CallStageLayout {
        val metrics = input.metrics
        if (metrics.width <= 0f || metrics.height <= 0f) return CallStageLayout.Empty
        val own = input.own?.takeIf { it.tileId in input.gridTileIds }
        val ids = input.gridTileIds
        val floats = own != null && ids.size <= 2
        val inline = if (floats) ids - own.tileId else ids
        val tiles = LinkedHashMap<String, Rect>()
        val positions = LinkedHashMap<String, CallStageLayout.GridPosition>()
        var fullBleedTileId: String? = null
        when {
            inline.isEmpty() -> Unit
            // One other person, alone on the stage: edge to edge, behind both bars (R4).
            inline.size == 1 && floats -> {
                fullBleedTileId = inline.single()
                tiles[inline.single()] = Rect(0f, -input.topBleed, metrics.width, metrics.height)
                positions[inline.single()] = CallStageLayout.GridPosition(0, 0)
            }
            metrics.isLandscape -> placeLandscape(inline, metrics, tiles, positions)
            else -> placePortrait(inline, metrics, tiles, positions)
        }
        val floating = own?.takeIf { floats }?.let {
            val area = input.floatingArea ?: Rect(0f, 0f, metrics.width, metrics.height)
            val rect = floatingRect(it.corner, floatingSize(it, metrics), area, metrics.margin)
            tiles[it.tileId] = rect
            FloatingOwnTile(it.tileId, rect, it.corner)
        }
        return CallStageLayout(
            tiles = tiles,
            gridPositions = positions,
            spotlight = null,
            spotlightTileId = null,
            contentHeight = metrics.height,
            hiddenTileIds = emptySet(),
            heroStack = null,
            floating = floating,
            fullBleedTileId = fullBleedTileId,
            isStatic = true,
        )
    }

    /**
     * Portrait (R5, R6, R11): 4:3 from the top, one column up to three tiles and two from four, as wide
     * as every row fits above the controls, each row centred, so a fifth tile alone sits in the middle.
     */
    private fun placePortrait(
        ids: List<String>,
        metrics: CallStageMetrics,
        tiles: MutableMap<String, Rect>,
        positions: MutableMap<String, CallStageLayout.GridPosition>,
    ) {
        val columns = if (ids.size <= 3) 1 else 2
        val rows = ceil(ids.size / columns.toFloat()).toInt()
        val gap = metrics.gap
        val available = metrics.height - metrics.controlsClearance - metrics.margin
        val widest = (metrics.width - 2 * metrics.margin - (columns - 1) * gap) / columns
        val tallest = (available - (rows - 1) * gap) / rows
        val width = min(widest, tallest * metrics.tileAspect)
        val height = width / metrics.tileAspect
        placeRows(ids, columns, Size(width, height), top = metrics.margin, metrics, tiles, positions)
    }

    /**
     * Landscape (R16): 4:3 in rows of at most four, each as wide as the stage allows and centred, the
     * rows together centred on the screen; shrunk only when the rows do not fit its height.
     */
    private fun placeLandscape(
        ids: List<String>,
        metrics: CallStageMetrics,
        tiles: MutableMap<String, Rect>,
        positions: MutableMap<String, CallStageLayout.GridPosition>,
    ) {
        val columns = min(ids.size, LANDSCAPE_ROW)
        val rows = ceil(ids.size / columns.toFloat()).toInt()
        val gap = metrics.gap
        val widest = (metrics.width - 2 * metrics.margin - (columns - 1) * gap) / columns
        val tallest = (metrics.height - 2 * metrics.margin - (rows - 1) * gap) / rows
        val width = min(widest, tallest * metrics.tileAspect)
        val height = width / metrics.tileAspect
        val blockHeight = rows * height + (rows - 1) * gap
        placeRows(ids, columns, Size(width, height), top = (metrics.height - blockHeight) / 2, metrics, tiles, positions)
    }

    private fun placeRows(
        ids: List<String>,
        columns: Int,
        size: Size,
        top: Float,
        metrics: CallStageMetrics,
        tiles: MutableMap<String, Rect>,
        positions: MutableMap<String, CallStageLayout.GridPosition>,
    ) {
        val gap = metrics.gap
        ids.chunked(columns).forEachIndexed { row, rowIds ->
            val rowWidth = rowIds.size * size.width + (rowIds.size - 1) * gap
            val left = (metrics.width - rowWidth) / 2
            val y = top + row * (size.height + gap)
            rowIds.forEachIndexed { column, id ->
                val x = left + column * (size.width + gap)
                tiles[id] = Rect(x, y, x + size.width, y + size.height)
                positions[id] = CallStageLayout.GridPosition(row, column)
            }
        }
    }

    /**
     * Our floating tile's size in pixels (R10, R17, R28): square with the camera off; otherwise the
     * picture's shape, or the stage's own before the first frame, our camera being upright in it.
     */
    fun floatingSize(own: OwnTileInput, metrics: CallStageMetrics): Size {
        val short = FLOATING_SHORT_SIDE * metrics.density
        val long = FLOATING_LONG_SIDE * metrics.density
        val isLandscape = own.videoAspect?.let { it > 1f } ?: metrics.isLandscape
        return when {
            !own.hasVideo -> Size(short, short)
            isLandscape -> Size(long, short)
            else -> Size(short, long)
        }
    }

    /** The rect for [corner], [margin] in from [area], the part of the stage the visible chrome leaves (R19). */
    fun floatingRect(corner: OwnTileCorner, size: Size, area: Rect, margin: Float): Rect {
        val left = if (corner.isLeft) area.left + margin else area.right - margin - size.width
        val top = if (corner.isTop) area.top + margin else area.bottom - margin - size.height
        return Rect(Offset(left, top), size)
    }

    /**
     * The corner a release takes our tile to (R21): the one nearest where its motion would carry it,
     * the velocity in pixels a second projected at a scroll's deceleration. A release at rest has no
     * velocity, which makes it a drop on the nearest corner.
     */
    fun releaseCorner(center: Offset, velocity: Offset, bounds: Rect): OwnTileCorner {
        val projected = center + velocity * (DECELERATION_RATE / (1 - DECELERATION_RATE) / 1000f)
        return OwnTileCorner.of(isLeft = projected.x < bounds.center.x, isTop = projected.y < bounds.center.y)
    }

    /** [offset] limited so that [rect] moved by it stays inside [bounds]: the tile cannot leave the stage (R21). */
    fun clampedDrag(rect: Rect, offset: Offset, bounds: Rect): Offset = Offset(
        offset.x.coerceIn(bounds.left - rect.left, (bounds.right - rect.right).coerceAtLeast(bounds.left - rect.left)),
        offset.y.coerceIn(bounds.top - rect.top, (bounds.bottom - rect.bottom).coerceAtLeast(bounds.top - rect.top)),
    )

    /**
     * Who the small call holds as its speaker, for Picture in Picture and the minimised tile (R15):
     * the one held while still speaking, so overlapping voices do not flip it; else the first remote
     * tile speaking; else the one held while still in the call. Never us.
     */
    fun speaker(tiles: List<CallTileData>, held: String?): String? {
        val remote = tiles.filterNot { it.isLocal || it.isScreenShare }
        return remote.firstOrNull { it.tileId == held && it.isActiveSpeaker }?.tileId
            ?: remote.firstOrNull { it.isActiveSpeaker }?.tileId
            ?: remote.firstOrNull { it.tileId == held }?.tileId
    }

    /** What Picture in Picture shows in a small call (R15): the speaker, else the first remote tile, else ours. */
    fun pictureInPictureTile(tiles: List<CallTileData>, speakerId: String?): CallTileData? =
        tiles.firstOrNull { it.tileId == speakerId }
            ?: tiles.firstOrNull { !it.isLocal }
            ?: tiles.firstOrNull { it.isLocal }
}
