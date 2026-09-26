/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.ui.geometry.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The arrangement `CallStage` animates towards, as arithmetic at the harness viewport (393x734 and
 * its landscape, spec 003 scenarios). Rects are exact and cheap here; a pixel test would only
 * approximate them. Rules cited by ID.
 */
class CallStageLayoutTest {
    @Test
    fun `a grid tile is 4 by 3 in both orientations and every tile is the same size`() {
        for (metrics in listOf(PORTRAIT, LANDSCAPE)) {
            val layout = rankedGrid(gridTiles = aCrowd(9), metrics = metrics)

            val sizes = layout.tiles.values.map { it.width to it.height }.toSet()
            // R12: one size. R10: 4:3.
            assertThat(sizes).hasSize(1)
            val (width, height) = sizes.single()
            assertThat(width / height).isWithin(TOLERANCE).of(4f / 3f)
        }
    }

    @Test
    fun `portrait has two width-driven columns, rows from the top, a partial last row left-aligned`() {
        val layout = rankedGrid(gridTiles = aCrowd(5), metrics = PORTRAIT)

        val tileWidth = (WIDTH - 2 * MARGIN - GAP) / 2
        val tileHeight = tileWidth * 3 / 4
        // R26, R30: half the grid width less the gap, however much space is left below.
        assertThat(layout.tiles.getValue("t0")).isEqualTo(Rect(MARGIN, MARGIN, MARGIN + tileWidth, MARGIN + tileHeight))
        assertThat(layout.tiles.getValue("t1").left).isWithin(TOLERANCE).of(MARGIN + tileWidth + GAP)
        assertThat(layout.tiles.getValue("t2").top).isWithin(TOLERANCE).of(MARGIN + tileHeight + GAP)
        // R29: the fifth tile is alone on row three, on the left.
        assertThat(layout.tiles.getValue("t4").left).isEqualTo(MARGIN)
        assertThat(layout.tiles.getValue("t4").top).isWithin(TOLERANCE).of(MARGIN + 2 * (tileHeight + GAP))
    }

    @Test
    fun `the portrait spotlight is the full stage width, edge to edge, 16 by 9, and the grid starts under it`() {
        val layout = rankedGrid(gridTiles = aCrowd(6), spotlight = "share", heroes = listOf("share"), metrics = PORTRAIT)

        // R13, contract B3: not inset to the grid's margins, not a fraction of the height.
        assertThat(layout.spotlight).isEqualTo(Rect(0f, 0f, WIDTH, WIDTH * 9 / 16))
        // R44: scrolled to the top, the first row sits directly under the spotlight.
        assertThat(layout.tiles.getValue("t0").top).isWithin(TOLERANCE).of(WIDTH * 9 / 16 + GAP)
        assertThat(layout.tiles.getValue("t0").left).isEqualTo(MARGIN)
        assertThat(layout.spotlightTileId).isEqualTo("share")
    }

    @Test
    fun `in landscape the spotlight takes the width one column leaves, at the full stage height`() {
        val layout = rankedGrid(gridTiles = aCrowd(6), spotlight = "share", heroes = listOf("share"), metrics = LANDSCAPE)

        val tileHeight = (LANDSCAPE.height - 2 * MARGIN - GAP) / 2
        val tileWidth = tileHeight * 4 / 3
        val columnLeft = LANDSCAPE.width - MARGIN - tileWidth
        // R14: all the width the column does not use, full height. Not a 16:9 box centred in the remainder.
        assertThat(layout.spotlight).isEqualTo(Rect(0f, 0f, columnLeft - GAP, LANDSCAPE.height))
        // R31: one column on the trailing side showing two whole tiles.
        val column = layout.tiles.values.map { it.left }.toSet()
        assertThat(column).hasSize(1)
        assertThat(column.single()).isWithin(TOLERANCE).of(columnLeft)
        assertThat(layout.tiles.getValue("t1").bottom).isAtMost(LANDSCAPE.height - MARGIN + TOLERANCE)
    }

    @Test
    fun `in landscape without a spotlight the grid is four fixed columns`() {
        val layout = rankedGrid(gridTiles = aCrowd(9), metrics = LANDSCAPE)

        // R32 as contract B4 sharpens it.
        val tileWidth = (LANDSCAPE.width - 2 * MARGIN - 3 * GAP) / 4
        assertThat(layout.tiles.values.map { it.left }.toSet()).hasSize(4)
        assertThat(layout.tiles.getValue("t3").left).isWithin(TOLERANCE).of(MARGIN + 3 * (tileWidth + GAP))
        assertThat(layout.tiles.getValue("t4").top).isWithin(TOLERANCE).of(MARGIN + tileWidth * 3 / 4 + GAP)
        assertThat(layout.tiles.getValue("t8").left).isEqualTo(MARGIN)
    }

    @Test
    fun `alone, our tile fills the stage`() {
        val layout = rankedGrid(gridTiles = listOf("own"), metrics = PORTRAIT)

        assertThat(layout.tiles.getValue("own")).isEqualTo(Rect(MARGIN, MARGIN, WIDTH - MARGIN, HEIGHT - MARGIN))
        assertThat(layout.spotlight).isNull()
        assertThat(layout.contentHeight).isEqualTo(HEIGHT)
    }

    @Test
    fun `two tiles share the stage equally, stacked in portrait and side by side in landscape`() {
        val portrait = rankedGrid(gridTiles = listOf("own", "b"), metrics = PORTRAIT)
        val own = portrait.tiles.getValue("own")
        val other = portrait.tiles.getValue("b")
        // R35: stacked, equal.
        assertThat(own.width).isEqualTo(WIDTH - 2 * MARGIN)
        assertThat(own.height).isWithin(TOLERANCE).of(other.height)
        assertThat(other.top).isWithin(TOLERANCE).of(own.bottom + GAP)
        assertThat(other.bottom).isWithin(TOLERANCE).of(HEIGHT - CLEARANCE)

        val landscape = rankedGrid(gridTiles = listOf("own", "b"), metrics = LANDSCAPE)
        val left = landscape.tiles.getValue("own")
        val right = landscape.tiles.getValue("b")
        // Contract B4: side by side, 4:3, centred on the stage's height.
        assertThat(left.top).isWithin(TOLERANCE).of(right.top)
        assertThat(right.left).isWithin(TOLERANCE).of(left.right + GAP)
        assertThat(left.width / left.height).isWithin(TOLERANCE).of(4f / 3f)
        assertThat((left.top + left.bottom) / 2).isWithin(TOLERANCE).of(LANDSCAPE.height / 2)
    }

    @Test
    fun `three tiles use the grid in portrait on a phone and one row in landscape`() {
        // R36: three full-width 4:3 rows do not fit above the controls at 393x734, so the grid.
        val portrait = rankedGrid(gridTiles = listOf("own", "b", "c"), metrics = PORTRAIT)
        assertThat(portrait.tiles.values.map { it.left }.toSet()).hasSize(2)
        assertThat(portrait.tiles.getValue("c").left).isEqualTo(MARGIN)

        // Tall enough, and three rows fit.
        val tall = rankedGrid(gridTiles = listOf("own", "b", "c"), metrics = PORTRAIT.copy(height = 1200f))
        assertThat(tall.tiles.values.map { it.left }.toSet()).containsExactly(MARGIN)
        assertThat(tall.tiles.getValue("own").width).isEqualTo(WIDTH - 2 * MARGIN)

        val landscape = rankedGrid(gridTiles = listOf("own", "b", "c"), metrics = LANDSCAPE)
        assertThat(landscape.tiles.values.map { it.top }.toSet()).hasSize(1)
        assertThat(landscape.tiles.getValue("c").right).isWithin(TOLERANCE).of(LANDSCAPE.width - MARGIN)
    }

    @Test
    fun `with a spotlight the ordinary grid is used at any count`() {
        // R34: two tiles under a spotlight are a grid row, not the shared-stage pair.
        val layout = rankedGrid(gridTiles = listOf("own", "b"), spotlight = "share", heroes = listOf("share"), metrics = PORTRAIT)

        val tileWidth = (WIDTH - 2 * MARGIN - GAP) / 2
        assertThat(layout.tiles.getValue("own").width).isWithin(TOLERANCE).of(tileWidth)
        assertThat(layout.tiles.getValue("b").top).isWithin(TOLERANCE).of(layout.tiles.getValue("own").top)
    }

    @Test
    fun `a hero that is not shown is hidden and the stack says which one is`() {
        val layout = rankedGrid(gridTiles = aCrowd(4), spotlight = "m", heroes = listOf("a", "m"), metrics = PORTRAIT)

        // R24, R19: not drawn, not in the grid; shown by identity at its position in the stack.
        assertThat(layout.hiddenTileIds).containsExactly("a")
        assertThat(layout.tiles.keys).doesNotContain("a")
        assertThat(layout.heroStack).isEqualTo(CallStageLayout.HeroStack(count = 2, shownIndex = 1))
    }

    @Test
    fun `the content height clears the control bar and is never shorter than the stage`() {
        val short = rankedGrid(gridTiles = aCrowd(2), spotlight = "share", heroes = listOf("share"), metrics = PORTRAIT)
        assertThat(short.contentHeight).isEqualTo(HEIGHT)

        val long = rankedGrid(gridTiles = aCrowd(20), metrics = PORTRAIT)
        val lastRowBottom = long.tiles.values.maxOf { it.bottom }
        // R44: scrolled to the end the last row's bottom is at the stage's height less the clearance.
        assertThat(long.contentHeight).isWithin(TOLERANCE).of(lastRowBottom + MARGIN + CLEARANCE)
        assertThat(long.maxScroll(HEIGHT)).isWithin(TOLERANCE).of(lastRowBottom + MARGIN + CLEARANCE - HEIGHT)
    }

    @Test
    fun `two hundred tiles do not overlap and the arrangement does not depend on the count`() {
        val layout = rankedGrid(gridTiles = aCrowd(200), spotlight = "share", heroes = listOf("share"), metrics = PORTRAIT)

        val rects = layout.tiles.values.toList()
        assertThat(rects).hasSize(200)
        for (i in rects.indices) {
            for (j in i + 1 until rects.size) {
                assertThat(rects[i].overlaps(rects[j])).isFalse()
            }
        }
        // R47: the first rows are where they are with twelve tiles too.
        val twelve = rankedGrid(gridTiles = aCrowd(12), spotlight = "share", heroes = listOf("share"), metrics = PORTRAIT)
        assertThat(layout.tiles.getValue("t7")).isEqualTo(twelve.tiles.getValue("t7"))
    }

    @Test
    fun `a stage with no size places nothing`() {
        assertThat(rankedGrid(gridTiles = aCrowd(3), metrics = PORTRAIT.copy(width = 0f, height = 0f))).isEqualTo(CallStageLayout.Empty)
    }

    @Test
    fun `visibility is live in the viewport, paused within a viewport of it, released beyond`() {
        val viewport = Rect(0f, 1000f, WIDTH, 1000f + HEIGHT)
        val inView = Rect(0f, 1200f, 100f, 1300f)
        val justAbove = Rect(0f, 500f, 100f, 600f)
        val farAbove = Rect(0f, 0f, 100f, 100f)

        // R48: one viewport beyond the visible area receives no picture.
        assertThat(CallTileVisibility.forRect(inView, viewport, wasLive = false)).isEqualTo(CallTileVisibility.Live)
        assertThat(CallTileVisibility.forRect(justAbove, viewport, wasLive = false)).isEqualTo(CallTileVisibility.Paused)
        assertThat(CallTileVisibility.forRect(farAbove, viewport, wasLive = false)).isEqualTo(CallTileVisibility.Released)
    }

    @Test
    fun `a live tile stays live until half a viewport past the edge`() {
        val viewport = Rect(0f, 1000f, WIDTH, 1000f + HEIGHT)
        val justAbove = Rect(0f, 800f, 100f, 900f)
        val wellAbove = Rect(0f, 400f, 100f, 500f)

        // R58: a tile bouncing at the edge does not flip its stream on every bounce.
        assertThat(CallTileVisibility.forRect(justAbove, viewport, wasLive = true)).isEqualTo(CallTileVisibility.Live)
        assertThat(CallTileVisibility.forRect(justAbove, viewport, wasLive = false)).isEqualTo(CallTileVisibility.Paused)
        assertThat(CallTileVisibility.forRect(wellAbove, viewport, wasLive = true)).isEqualTo(CallTileVisibility.Paused)
    }

    private fun rankedGrid(
        gridTiles: List<String>,
        spotlight: String? = null,
        heroes: List<String> = emptyList(),
        metrics: CallStageMetrics,
    ) = CallStageArrangement.RankedGrid.compute(
        CallStageLayout.Input(gridTileIds = gridTiles, spotlightTileId = spotlight, heroIds = heroes, metrics = metrics),
    )

    private fun aCrowd(size: Int) = List(size) { "t$it" }

    private companion object {
        const val WIDTH = 393f
        const val HEIGHT = 734f
        const val GAP = 8f
        const val MARGIN = 8f

        /** The harness viewport's bottom inset plus the control bar: `393x734 bottom 34`, bar 88. */
        const val CLEARANCE = 88f + 34f
        const val TOLERANCE = 0.01f
        val PORTRAIT = CallStageMetrics(width = WIDTH, height = HEIGHT, gap = GAP, margin = MARGIN, controlsClearance = CLEARANCE)
        val LANDSCAPE = PORTRAIT.copy(width = HEIGHT, height = WIDTH)
    }
}
