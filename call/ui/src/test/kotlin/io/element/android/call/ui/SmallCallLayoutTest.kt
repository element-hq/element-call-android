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
import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.id.UserId
import org.junit.Test

/** Spec 019's arithmetic at the harness viewport (393x734 and its landscape), in pixels at density 1. */
class SmallCallLayoutTest {
    @Test
    fun `it applies up to five tiles ours included, and not from six`() {
        val five = listOf(aTile(OWN, isLocal = true)) + (1..4).map { aTile("r$it") }
        assertThat(SmallCallLayout.applies(five)).isTrue()
        // R1, R33: the sixth tile switches, with no margin.
        assertThat(SmallCallLayout.applies(five + aTile("r5"))).isFalse()
        assertThat(SmallCallLayout.applies(five.take(1))).isTrue()
    }

    @Test
    fun `a remote share switches to the grid and ours does not, nor is a share counted`() {
        val three = listOf(aTile(OWN, isLocal = true), aTile("r1"), aTile("r2"))
        // R8.
        assertThat(SmallCallLayout.applies(three + aTile("r1", isShare = true))).isFalse()
        // R1: a share is not a tile of the count; ours never makes the grid.
        assertThat(SmallCallLayout.applies(three + aTile(OWN, isLocal = true, isShare = true))).isTrue()
    }

    @Test
    fun `alone, ours floats bottom right and nothing else is placed`() {
        for (metrics in listOf(PORTRAIT, LANDSCAPE)) {
            val layout = small(listOf(OWN), metrics)

            // R3, R18.
            assertThat(layout.floating?.corner).isEqualTo(OwnTileCorner.BOTTOM_RIGHT)
            assertThat(layout.floating?.rect?.right).isEqualTo(metrics.width - MARGIN)
            assertThat(layout.floating?.rect?.bottom).isEqualTo(metrics.height - MARGIN)
            assertThat(layout.tiles.keys).containsExactly(OWN)
            assertThat(layout.fullBleedTileId).isNull()
            assertThat(layout.isStatic).isTrue()
        }
    }

    @Test
    fun `one to one, the other person is edge to edge behind the bars and ours floats`() {
        for (metrics in listOf(PORTRAIT, LANDSCAPE)) {
            val layout = small(listOf(OWN, "b"), metrics, topBleed = TOP_BLEED)

            // R4: behind the top bar too, so above the stage's own top.
            assertThat(layout.tiles.getValue("b")).isEqualTo(Rect(0f, -TOP_BLEED, metrics.width, metrics.height))
            assertThat(layout.fullBleedTileId).isEqualTo("b")
            assertThat(layout.floating?.tileId).isEqualTo(OWN)
        }
    }

    @Test
    fun `three in portrait are one 4 by 3 column, ours first, as wide as three rows fit above the controls, centred`() {
        val layout = small(listOf(OWN, "b", "c"), PORTRAIT)

        val height = (HEIGHT - CLEARANCE - MARGIN - 2 * GAP) / 3
        val width = height * 4 / 3
        // R5: the three rows would not fit at full width here, so narrower and centred.
        assertThat(width).isLessThan(WIDTH - 2 * MARGIN)
        assertThat(layout.tiles.getValue(OWN)).isEqualTo(Rect((WIDTH - width) / 2, MARGIN, (WIDTH + width) / 2, MARGIN + height))
        assertThat(layout.tiles.getValue("c").top).isWithin(TOLERANCE).of(MARGIN + 2 * (height + GAP))
        assertThat(layout.tiles.getValue("c").bottom).isAtMost(HEIGHT - CLEARANCE)
        assertThat(layout.floating).isNull()
    }

    @Test
    fun `three in a tall portrait take the full width`() {
        val tall = PORTRAIT.copy(height = 1200f)
        val layout = small(listOf(OWN, "b", "c"), tall)

        assertThat(layout.tiles.getValue(OWN).left).isEqualTo(MARGIN)
        assertThat(layout.tiles.getValue(OWN).width).isEqualTo(WIDTH - 2 * MARGIN)
    }

    @Test
    fun `four in portrait are a 2 by 2 from the top, ours top left`() {
        val layout = small(listOf(OWN, "b", "c", "d"), PORTRAIT)

        val width = (WIDTH - 2 * MARGIN - GAP) / 2
        // R6.
        assertThat(layout.tiles.getValue(OWN)).isEqualTo(Rect(MARGIN, MARGIN, MARGIN + width, MARGIN + width * 3 / 4))
        assertThat(layout.gridPositions.getValue("d")).isEqualTo(CallStageLayout.GridPosition(1, 1))
    }

    @Test
    fun `five in portrait are two rows of two, then the fifth alone the same size and centred`() {
        val layout = small(listOf(OWN, "b", "c", "d", "e"), PORTRAIT)

        val fifth = layout.tiles.getValue("e")
        // R11.
        assertThat(fifth.size).isEqualTo(layout.tiles.getValue(OWN).size)
        assertThat(fifth.center.x).isWithin(TOLERANCE).of(WIDTH / 2)
        assertThat(layout.gridPositions.getValue("e")).isEqualTo(CallStageLayout.GridPosition(2, 0))
        assertThat(fifth.bottom).isAtMost(HEIGHT - CLEARANCE)
    }

    @Test
    fun `three and four in landscape are one row as wide as the stage allows, centred on the screen`() {
        for (count in 3..4) {
            val ids = listOf(OWN) + ('b'..'z').take(count - 1).map { it.toString() }
            val layout = small(ids, LANDSCAPE)

            val width = (HEIGHT - 2 * MARGIN - (count - 1) * GAP) / count
            val own = layout.tiles.getValue(OWN)
            // R16: ours inline, first, the same size as the others.
            assertThat(own.left).isWithin(TOLERANCE).of(MARGIN)
            assertThat(own.width).isWithin(TOLERANCE).of(width)
            assertThat(own.center.y).isWithin(TOLERANCE).of(WIDTH / 2)
            layout.tiles.values.forEach { assertThat(it.width).isWithin(TOLERANCE).of(width) }
            assertThat(layout.floating).isNull()
        }
    }

    @Test
    fun `five in landscape are a row of four and a row of one, centred, shrunk only to fit the height`() {
        val layout = small(listOf(OWN, "b", "c", "d", "e"), LANDSCAPE)

        val own = layout.tiles.getValue(OWN)
        val fifth = layout.tiles.getValue("e")
        assertThat(fifth.center.x).isWithin(TOLERANCE).of(HEIGHT / 2)
        val blockCentre = (own.top + fifth.bottom) / 2
        assertThat(blockCentre).isWithin(TOLERANCE).of(WIDTH / 2)
        // R16: two rows of the row's full width fit here, so no shrinking.
        assertThat(own.width).isWithin(TOLERANCE).of((HEIGHT - 2 * MARGIN - 3 * GAP) / 4)
        assertThat(fifth.size).isEqualTo(own.size)
    }

    @Test
    fun `five on a short landscape stage shrink until the two rows fit its height`() {
        val short = LANDSCAPE.copy(height = 250f)
        val layout = small(listOf(OWN, "b", "c", "d", "e"), short)

        val own = layout.tiles.getValue(OWN)
        val fifth = layout.tiles.getValue("e")
        // R16: shrunk only for the height, to exactly the stage less its margins.
        assertThat(fifth.bottom - own.top).isWithin(TOLERANCE).of(250f - 2 * MARGIN)
        assertThat(own.width).isLessThan((HEIGHT - 2 * MARGIN - 3 * GAP) / 4)
        assertThat(own.width / own.height).isWithin(TOLERANCE).of(4f / 3f)
    }

    @Test
    fun `our floating tile is upright, sideways or square by its picture, the stage's shape before the first frame`() {
        fun size(hasVideo: Boolean, aspect: Float?, metrics: CallStageMetrics) =
            SmallCallLayout.floatingSize(OwnTileInput(OWN, hasVideo, aspect, OwnTileCorner.BOTTOM_RIGHT), metrics)

        // R10, R17, R28.
        assertThat(size(hasVideo = true, aspect = 9f / 16f, LANDSCAPE)).isEqualTo(Size(100f, 150f))
        assertThat(size(hasVideo = true, aspect = 16f / 9f, PORTRAIT)).isEqualTo(Size(150f, 100f))
        assertThat(size(hasVideo = false, aspect = 16f / 9f, PORTRAIT)).isEqualTo(Size(100f, 100f))
        assertThat(size(hasVideo = true, aspect = null, PORTRAIT)).isEqualTo(Size(100f, 150f))
        assertThat(size(hasVideo = true, aspect = null, LANDSCAPE)).isEqualTo(Size(150f, 100f))
        // In dp: twice the pixels at density 2.
        assertThat(size(hasVideo = false, aspect = null, PORTRAIT.copy(density = 2f))).isEqualTo(Size(200f, 200f))
    }

    @Test
    fun `each corner is a margin in from the area the chrome leaves`() {
        val area = Rect(0f, 40f, WIDTH, HEIGHT - CLEARANCE)
        val size = Size(100f, 150f)

        // R19: clear of the visible bars, whichever corner.
        assertThat(SmallCallLayout.floatingRect(OwnTileCorner.TOP_LEFT, size, area, MARGIN)).isEqualTo(Rect(Offset(MARGIN, 40f + MARGIN), size))
        assertThat(SmallCallLayout.floatingRect(OwnTileCorner.BOTTOM_RIGHT, size, area, MARGIN))
            .isEqualTo(Rect(Offset(WIDTH - MARGIN - 100f, HEIGHT - CLEARANCE - MARGIN - 150f), size))
        val layout = small(listOf(OWN, "b"), PORTRAIT, corner = OwnTileCorner.TOP_RIGHT, floatingArea = area)
        assertThat(layout.floating?.rect?.top).isEqualTo(40f + MARGIN)
        assertThat(layout.floating?.rect?.right).isEqualTo(WIDTH - MARGIN)
    }

    @Test
    fun `a drop goes to the nearest corner`() {
        val bounds = Rect(0f, 0f, WIDTH, HEIGHT)
        // R21: at rest, the quadrant the tile was let go in.
        assertThat(SmallCallLayout.releaseCorner(Offset(50f, 50f), Offset.Zero, bounds)).isEqualTo(OwnTileCorner.TOP_LEFT)
        assertThat(SmallCallLayout.releaseCorner(Offset(300f, 50f), Offset.Zero, bounds)).isEqualTo(OwnTileCorner.TOP_RIGHT)
        assertThat(SmallCallLayout.releaseCorner(Offset(50f, 600f), Offset.Zero, bounds)).isEqualTo(OwnTileCorner.BOTTOM_LEFT)
        assertThat(SmallCallLayout.releaseCorner(Offset(300f, 600f), Offset.Zero, bounds)).isEqualTo(OwnTileCorner.BOTTOM_RIGHT)
    }

    @Test
    fun `a flick reaches the corner it was thrown towards, and a slow release barely moves the projection`() {
        val bounds = Rect(0f, 0f, WIDTH, HEIGHT)
        val bottomRight = Offset(300f, 600f)
        // R21: a quick flick up from the bottom right ends top right.
        assertThat(SmallCallLayout.releaseCorner(bottomRight, Offset(0f, -2_000f), bounds)).isEqualTo(OwnTileCorner.TOP_RIGHT)
        assertThat(SmallCallLayout.releaseCorner(bottomRight, Offset(-2_000f, -2_000f), bounds)).isEqualTo(OwnTileCorner.TOP_LEFT)
        assertThat(SmallCallLayout.releaseCorner(bottomRight, Offset(0f, -100f), bounds)).isEqualTo(OwnTileCorner.BOTTOM_RIGHT)
    }

    @Test
    fun `a drag cannot take the tile off the stage`() {
        val bounds = Rect(0f, 0f, WIDTH, HEIGHT)
        val rect = Rect(Offset(277f, 568f), Size(100f, 150f))

        // R21.
        assertThat(SmallCallLayout.clampedDrag(rect, Offset(500f, 500f), bounds)).isEqualTo(Offset(WIDTH - rect.right, HEIGHT - rect.bottom))
        assertThat(SmallCallLayout.clampedDrag(rect, Offset(-1_000f, -1_000f), bounds)).isEqualTo(Offset(-rect.left, -rect.top))
        assertThat(SmallCallLayout.clampedDrag(rect, Offset(-10f, 5f), bounds)).isEqualTo(Offset(-10f, 5f))
    }

    @Test
    fun `the speaker is held while speaking, then the first speaking, then held through silence, never us`() {
        val b = aTile("b")
        val c = aTile("c")
        val us = aTile(OWN, isLocal = true, isSpeaking = true)
        // R15.
        assertThat(SmallCallLayout.speaker(listOf(us, b, c), held = null)).isNull()
        assertThat(SmallCallLayout.speaker(listOf(us, b, c.speaking()), held = null)).isEqualTo("c")
        // Two at once: the one held stays.
        assertThat(SmallCallLayout.speaker(listOf(us, b.speaking(), c.speaking()), held = "c")).isEqualTo("c")
        assertThat(SmallCallLayout.speaker(listOf(us, b.speaking(), c), held = "c")).isEqualTo("b")
        // Silence keeps the last; a speaker who left is let go.
        assertThat(SmallCallLayout.speaker(listOf(us, b, c), held = "c")).isEqualTo("c")
        assertThat(SmallCallLayout.speaker(listOf(us, b), held = "c")).isNull()
    }

    @Test
    fun `picture in picture shows the speaker, else the first remote tile, else ours`() {
        val us = aTile(OWN, isLocal = true)
        val tiles = listOf(us, aTile("b"), aTile("c"))
        // R15.
        assertThat(SmallCallLayout.pictureInPictureTile(tiles, speakerId = "c")?.tileId).isEqualTo("c")
        assertThat(SmallCallLayout.pictureInPictureTile(tiles, speakerId = null)?.tileId).isEqualTo("b")
        assertThat(SmallCallLayout.pictureInPictureTile(listOf(us), speakerId = null)?.tileId).isEqualTo(OWN)
    }

    private fun small(
        ids: List<String>,
        metrics: CallStageMetrics,
        corner: OwnTileCorner = OwnTileCorner.Initial,
        topBleed: Float = 0f,
        floatingArea: Rect? = null,
    ) = CallStageArrangement.SmallCall.compute(
        CallStageLayout.Input(
            gridTileIds = ids,
            spotlightTileId = null,
            heroIds = emptyList(),
            metrics = metrics,
            own = OwnTileInput(OWN, hasVideo = true, videoAspect = null, corner = corner),
            topBleed = topBleed,
            floatingArea = floatingArea,
        ),
    )

    private fun aTile(id: String, isLocal: Boolean = false, isShare: Boolean = false, isSpeaking: Boolean = false) = CallTileData(
        memberId = id,
        userId = UserId("@$id:example.com"),
        roomMember = null,
        isLocal = isLocal,
        isMuted = false,
        isActiveSpeaker = isSpeaking && !isShare,
        hasVideo = true,
        isVideoMirrored = false,
        streamKind = if (isShare) MatrixRtcStreamKind.SCREEN_SHARE else MatrixRtcStreamKind.CAMERA,
        isHero = isShare,
    )

    private fun CallTileData.speaking() = copy(isActiveSpeaker = true)

    private companion object {
        const val OWN = "own"
        const val WIDTH = 393f
        const val HEIGHT = 734f
        const val GAP = 16f
        const val MARGIN = 16f
        const val TOP_BLEED = 100f

        /** The harness viewport's bottom inset plus the control bar: `393x734 bottom 34`, bar 88. */
        const val CLEARANCE = 88f + 34f
        const val TOLERANCE = 0.01f
        val PORTRAIT = CallStageMetrics(width = WIDTH, height = HEIGHT, gap = GAP, margin = MARGIN, controlsClearance = CLEARANCE)
        val LANDSCAPE = PORTRAIT.copy(width = HEIGHT, height = WIDTH)
    }
}
