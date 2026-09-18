/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.geometry.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The arrangement `CallTileLayout` animates towards.
 *
 * Worth testing on its own because it is the one part of the tile layout that is pure: everything
 * else in that file is springs and GL renderers, which need a device to say anything about, while
 * "where does everyone go" is arithmetic that can be wrong quietly - a tile a few pixels off the
 * bottom of a phone looks like a rendering bug rather than like a layout one.
 */
class CallTileLayoutTest {
    @Test
    fun `everyone gets exactly one slot`() {
        val members = listOf("alice", "bob", "carol")

        val slots = computeSlots(members, spotlightTileId = "alice", WIDTH, HEIGHT, SPACING)

        assertThat(slots.keys).containsExactlyElementsIn(members)
    }

    /**
     * The spotlighted member is in the spotlight and *not* also in the strip.
     *
     * Not a cosmetic point: two tiles for one member means two collectors on one video stream, which
     * is a native crash rather than a duplicated face. It was one, before `stripParticipants`
     * existed.
     */
    @Test
    fun `the spotlighted member is not also given a strip slot`() {
        val slots = computeSlots(listOf("alice", "bob"), spotlightTileId = "alice", WIDTH, HEIGHT, SPACING)

        val spotlight = slots.getValue("alice")
        val strip = slots.getValue("bob")
        assertThat(spotlight.top).isEqualTo(0f)
        assertThat(strip.top).isAtLeast(spotlight.bottom)
    }

    /**
     * With nobody spotlighted - which is what a call nobody else has joined yet looks like, since the
     * spotlight is never ourselves - the strip has the whole area and centres in it, rather than
     * sitting against the top under a spotlight that is not there.
     */
    @Test
    fun `with nobody spotlighted the strip centres in the whole area`() {
        val slots = computeSlots(listOf("alice", "bob"), spotlightTileId = null, WIDTH, HEIGHT, SPACING)

        assertThat(slots).hasSize(2)
        val above = slots.values.minOf { it.top }
        val below = HEIGHT - slots.values.maxOf { it.bottom }
        assertThat(above).isWithin(TOLERANCE).of(below)
    }

    /**
     * The property that matters at every size: nobody is drawn off the edge and nobody overlaps.
     *
     * Checked across a range of member counts because the failure is count-dependent - the grid
     * gives up width as columns are added and height as rows are - and a fixed two-column strip,
     * which is what this replaced, runs off the bottom from about six.
     */
    @Test
    fun `tiles stay inside the area and never overlap, however many there are`() {
        for (count in 1..12) {
            val members = List(count) { "member$it" }

            val slots = computeSlots(members, spotlightTileId = members.first(), WIDTH, HEIGHT, SPACING)

            val rects = slots.values.toList()
            assertThat(rects).hasSize(count)
            rects.forEach { rect ->
                assertThat(rect.left).isAtLeast(-TOLERANCE)
                assertThat(rect.top).isAtLeast(-TOLERANCE)
                assertThat(rect.right).isAtMost(WIDTH + TOLERANCE)
                assertThat(rect.bottom).isAtMost(HEIGHT + TOLERANCE)
                assertThat(rect.width).isGreaterThan(0f)
                assertThat(rect.height).isGreaterThan(0f)
            }
            for (i in rects.indices) {
                for (j in i + 1 until rects.size) {
                    assertThat(rects[i] overlaps rects[j]).isFalse()
                }
            }
        }
    }

    /**
     * A member joining moves the others rather than shrinking the tiles they are already in - which
     * is the whole reason the slots are computed from the member list instead of being a fixed grid,
     * and the thing the animation exists to make legible.
     */
    @Test
    fun `adding a member changes where the existing ones are`() {
        val before = computeSlots(listOf("alice", "bob"), spotlightTileId = "alice", WIDTH, HEIGHT, SPACING)
        val after = computeSlots(listOf("alice", "bob", "carol"), spotlightTileId = "alice", WIDTH, HEIGHT, SPACING)

        assertThat(after.getValue("bob")).isNotEqualTo(before.getValue("bob"))
        // The spotlight is the one fixed point: the strip reflows underneath it.
        assertThat(after.getValue("alice")).isEqualTo(before.getValue("alice"))
    }

    /**
     * Promotion is a change of rectangle for a member who keeps their identity, which is exactly what
     * lets a single keeping composable - and so a single GL renderer - move between the two.
     */
    @Test
    fun `promoting a member swaps the two rectangles`() {
        val before = computeSlots(listOf("alice", "bob"), spotlightTileId = "alice", WIDTH, HEIGHT, SPACING)
        val after = computeSlots(listOf("alice", "bob"), spotlightTileId = "bob", WIDTH, HEIGHT, SPACING)

        assertThat(after.getValue("bob")).isEqualTo(before.getValue("alice"))
        assertThat(after.getValue("alice")).isEqualTo(before.getValue("bob"))
    }

    @Test
    fun `an area with no room in it produces no slots rather than nonsense`() {
        assertThat(computeSlots(listOf("alice"), spotlightTileId = null, width = 0f, height = 0f, SPACING)).isEmpty()
    }

    /**
     * Held wide, the design turns: the spotlight goes to the left and the strip becomes a column
     * down the right, rather than the portrait arrangement squashed into a letterbox.
     */
    @Test
    fun `in landscape the spotlight is on the left and the strip is a column on the right`() {
        val members = listOf("alice", "bob", "carol", "dave")

        val slots = computeSlots(members, spotlightTileId = "alice", LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT, SPACING)

        val spotlight = slots.getValue("alice")
        val strip = members.drop(1).map { slots.getValue(it) }
        assertThat(spotlight.left).isEqualTo(0f)
        assertThat(spotlight.height).isEqualTo(LANDSCAPE_HEIGHT)
        // One column: everyone in the strip shares a left edge, and all of them are clear of the
        // spotlight.
        assertThat(strip.map { it.left }.toSet()).hasSize(1)
        strip.forEach { assertThat(it.left).isAtLeast(spotlight.right) }
        // ...and they are stacked, in order.
        strip.zipWithNext().forEach { (above, below) -> assertThat(below.top).isAtLeast(above.bottom) }
    }

    @Test
    fun `landscape tiles stay inside the area and never overlap, however many there are`() {
        for (count in 1..8) {
            val members = List(count) { "member$it" }

            val slots = computeSlots(members, members.first(), LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT, SPACING)

            val rects = slots.values.toList()
            assertThat(rects).hasSize(count)
            rects.forEach { rect ->
                assertThat(rect.left).isAtLeast(-TOLERANCE)
                assertThat(rect.top).isAtLeast(-TOLERANCE)
                assertThat(rect.right).isAtMost(LANDSCAPE_WIDTH + TOLERANCE)
                assertThat(rect.bottom).isAtMost(LANDSCAPE_HEIGHT + TOLERANCE)
                assertThat(rect.width).isGreaterThan(0f)
                assertThat(rect.height).isGreaterThan(0f)
            }
            for (i in rects.indices) {
                for (j in i + 1 until rects.size) {
                    assertThat(rects[i] overlaps rects[j]).isFalse()
                }
            }
        }
    }

    /**
     * Rotating keeps everyone, and only moves them.
     *
     * The point of the whole arrangement-as-a-function approach: `MainActivity` handles the
     * orientation change rather than being recreated, so a rotation is nothing but a new set of
     * rectangles for the same composables - which animate there, with no video renderer rebuilt.
     */
    @Test
    fun `rotating moves everyone without adding or losing anyone`() {
        val members = listOf("alice", "bob", "carol")

        val portrait = computeSlots(members, "alice", WIDTH, HEIGHT, SPACING)
        val landscape = computeSlots(members, "alice", LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT, SPACING)

        assertThat(landscape.keys).isEqualTo(portrait.keys)
        members.forEach { assertThat(landscape.getValue(it)).isNotEqualTo(portrait.getValue(it)) }
    }

    /**
     * The band is sized from the tiles in it, so it neither takes a third of a landscape screen for
     * one other person nor squeezes to a sliver for a crowd - and whatever it takes, it uses. Width
     * reserved and then left empty is width taken off the spotlight for nothing, which is what an
     * earlier version of this did for larger calls.
     */
    @Test
    fun `the landscape band stays within its bounds and is filled`() {
        val bandFor = { count: Int ->
            val members = List(count + 1) { "member$it" }
            val slots = computeSlots(members, members.first(), LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT, SPACING)
            val strip = members.drop(1).map { slots.getValue(it) }
            strip.maxOf { it.right } - strip.minOf { it.left }
        }

        assertThat(bandFor(1)).isAtMost(LANDSCAPE_WIDTH * 0.28f + TOLERANCE)
        assertThat(bandFor(8)).isAtLeast(LANDSCAPE_WIDTH * 0.14f - TOLERANCE)
    }

    /** Whatever the band takes from the spotlight, the tiles in it use. */
    @Test
    fun `the landscape band leaves no width unused`() {
        for (count in 1..8) {
            val members = List(count + 1) { "member$it" }

            val slots = computeSlots(members, members.first(), LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT, SPACING)

            val strip = members.drop(1).map { slots.getValue(it) }
            val band = strip.maxOf { it.right } - strip.minOf { it.left }
            val takenFromSpotlight = LANDSCAPE_WIDTH - slots.getValue("member0").right - SPACING
            assertThat(band).isWithin(TOLERANCE).of(takenFromSpotlight)
        }
    }

    /**
     * One-to-one: the other person has the whole area and we are a thumbnail in the bottom-end
     * corner, at the design's proportion and a margin in from the edges.
     */
    @Test
    fun `in one-to-one the remote fills the area and the local is a thumbnail in the bottom-end corner`() {
        val slots = computeSlots(listOf("me", "them"), spotlightTileId = "them", WIDTH, HEIGHT, SPACING, layout = CallLayout.OneToOne)

        assertThat(slots.getValue("them")).isEqualTo(Rect(0f, 0f, WIDTH, HEIGHT))
        val thumbnail = slots.getValue("me")
        assertThat(thumbnail.right).isWithin(TOLERANCE).of(WIDTH - 2 * SPACING)
        assertThat(thumbnail.bottom).isWithin(TOLERANCE).of(HEIGHT - 2 * SPACING)
        assertThat(thumbnail.width).isWithin(TOLERANCE).of(WIDTH * 0.38f)
        assertThat(thumbnail.width / thumbnail.height).isWithin(0.01f).of(2f / 3f)
        assertThat(thumbnail.left).isAtLeast(0f)
        assertThat(thumbnail.top).isAtLeast(0f)
    }

    /**
     * Turned sideways the thumbnail turns too - the camera frame is landscape then, and the renderer
     * centre-crops - and it stays clear of whatever the caller says is floating over the bottom.
     */
    @Test
    fun `the one-to-one thumbnail turns with the phone and respects the bottom inset`() {
        val insets = EdgeInsets(left = 0f, top = 0f, right = 0f, bottom = 300f)

        val slots = computeSlots(
            tileIds = listOf("me", "them"),
            spotlightTileId = "them",
            width = LANDSCAPE_WIDTH,
            height = LANDSCAPE_HEIGHT,
            spacing = SPACING,
            layout = CallLayout.OneToOne,
            pipInsets = insets,
        )

        val thumbnail = slots.getValue("me")
        assertThat(thumbnail.width / thumbnail.height).isWithin(0.01f).of(3f / 2f)
        assertThat(thumbnail.height).isWithin(TOLERANCE).of(LANDSCAPE_HEIGHT * 0.38f)
        assertThat(thumbnail.bottom).isWithin(TOLERANCE).of(LANDSCAPE_HEIGHT - insets.bottom - 2 * SPACING)
        assertThat(thumbnail.right).isWithin(TOLERANCE).of(LANDSCAPE_WIDTH - 2 * SPACING)
        // The other person still has everything: the inset is for us, not for them.
        assertThat(slots.getValue("them")).isEqualTo(Rect(0f, 0f, LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT))
    }

    /** On a nearly square area the thumbnail's long side is capped rather than taking half the other person. */
    @Test
    fun `the one-to-one thumbnail is capped to half the area on a square screen`() {
        val slots = computeSlots(listOf("me", "them"), spotlightTileId = "them", 1000f, 1000f, SPACING, layout = CallLayout.OneToOne)

        val thumbnail = slots.getValue("me")
        assertThat(thumbnail.height).isAtMost(500f + TOLERANCE)
        assertThat(thumbnail.width / thumbnail.height).isWithin(0.01f).of(2f / 3f)
    }

    /**
     * The one-to-one arrangement only exists for two tiles. Asked for it with any other shape, the
     * function answers with the group arrangement rather than something half way between, so the
     * caller can hand over the state's layout without checking the tiles first.
     */
    @Test
    fun `one-to-one with a third tile falls back to the group arrangement`() {
        val members = listOf("me", "them", "someone")

        val oneToOne = computeSlots(members, spotlightTileId = "them", WIDTH, HEIGHT, SPACING, layout = CallLayout.OneToOne)
        val group = computeSlots(members, spotlightTileId = "them", WIDTH, HEIGHT, SPACING, layout = CallLayout.Group)

        assertThat(oneToOne).isEqualTo(group)
    }

    @Test
    fun `one-to-one with nobody spotlighted falls back to the grid`() {
        val oneToOne = computeSlots(listOf("me"), spotlightTileId = null, WIDTH, HEIGHT, SPACING, layout = CallLayout.OneToOne)
        val group = computeSlots(listOf("me"), spotlightTileId = null, WIDTH, HEIGHT, SPACING, layout = CallLayout.Group)

        assertThat(oneToOne).isEqualTo(group)
    }

    /**
     * While the grid can hold everyone two across at full size nothing changes: there is no strip
     * to scroll, and the slots are the grid's. Scrolling is opt-in, so every caller that does not
     * ask for it - every other test in this file included - gets exactly what it always got.
     */
    @Test
    fun `a call the grid can hold does not scroll`() {
        // Four in the strip: two rows of two, which fit under the spotlight.
        val members = aCrowd(5)

        val arrangement = computeArrangement(members, members.first(), WIDTH, HEIGHT, SPACING, canScroll = true)

        assertThat(arrangement.strip).isNull()
        assertThat(arrangement.slots).isEqualTo(computeSlots(members, members.first(), WIDTH, HEIGHT, SPACING))
    }

    /**
     * The grid gives way exactly when it would have to shrink the tiles to fit another row: with
     * six in the strip the third row of two does not fit under the spotlight, so instead of three
     * smaller rows the strip keeps its size and scrolls.
     */
    @Test
    fun `the grid gives way to scrolling when a row no longer fits at full size`() {
        val fits = computeArrangement(aCrowd(5), "member0", WIDTH, HEIGHT, SPACING, canScroll = true)
        val overflows = computeArrangement(aCrowd(7), "member0", WIDTH, HEIGHT, SPACING, canScroll = true)

        assertThat(fits.strip).isNull()
        assertThat(overflows.strip).isNotNull()
        // The same tile size either side of the switch: scrolling never shrinks anyone.
        assertThat(overflows.slots.getValue("member1").width).isWithin(TOLERANCE).of(fits.slots.getValue("member1").width)
    }

    /**
     * Past that the strip is pages of full-size tiles, two across and as many rows as fit under the
     * spotlight - which stays exactly where the grid put it - laid side by side to the right of the
     * first, with room kept under them for the dots.
     */
    @Test
    fun `a large call pages a two-column grid under the spotlight`() {
        val members = aCrowd(11)
        val indicator = 60f

        val arrangement = computeArrangement(members, members.first(), WIDTH, HEIGHT, SPACING, canScroll = true, pageIndicatorHeight = indicator)

        val strip = arrangement.strip!!
        val pages = strip.pages!!
        assertThat(strip.orientation).isEqualTo(Orientation.Horizontal)
        assertThat(strip.tileIds).containsExactlyElementsIn(members.drop(1))
        val spotlight = arrangement.slots.getValue(members.first())
        assertThat(spotlight).isEqualTo(computeSlots(members, members.first(), WIDTH, HEIGHT, SPACING).getValue(members.first()))
        assertThat(strip.viewport).isEqualTo(Rect(0f, spotlight.bottom + SPACING, WIDTH, HEIGHT - indicator))
        assertThat(pages.indicator).isEqualTo(Rect(0f, HEIGHT - indicator, WIDTH, HEIGHT))
        // Two rows of two fit under the spotlight: four to a page, ten in the strip, three pages.
        assertThat(pages.count).isEqualTo(3)
        assertThat(pages.stride).isWithin(TOLERANCE).of(WIDTH + SPACING)
        val tileWidth = (WIDTH - SPACING) / 2
        val tileHeight = tileWidth * 3 / 4
        val pageTop = strip.viewport.top + (strip.viewport.height - (2 * tileHeight + SPACING)) / 2
        members.drop(1).forEachIndexed { index, member ->
            val slot = arrangement.slots.getValue(member)
            val page = index / 4
            val row = index % 4 / 2
            val column = index % 2
            assertThat(slot.width).isWithin(TOLERANCE).of(tileWidth)
            assertThat(slot.height).isWithin(TOLERANCE).of(tileHeight)
            assertThat(slot.left).isWithin(TOLERANCE).of(page * pages.stride + column * (tileWidth + SPACING))
            assertThat(slot.top).isWithin(TOLERANCE).of(pageTop + row * (tileHeight + SPACING))
        }
        // Settles exactly on the last page.
        assertThat(strip.maxScroll).isWithin(TOLERANCE).of(2 * pages.stride)
        assertThat(pages.pageAt(0f)).isEqualTo(0)
        assertThat(pages.pageAt(pages.stride * 1.4f)).isEqualTo(1)
        assertThat(pages.pageAt(strip.maxScroll)).isEqualTo(2)
    }

    /**
     * Held wide the strip is the single column down the right the grid already gives it, at the
     * band's widest, scrolling freely rather than in pages, and the spotlight has the left.
     */
    @Test
    fun `a large call held sideways scrolls a column of tiles beside the spotlight`() {
        val members = aCrowd(11)

        val arrangement = computeArrangement(members, members.first(), LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT, SPACING, canScroll = true)

        val strip = arrangement.strip!!
        assertThat(strip.orientation).isEqualTo(Orientation.Vertical)
        assertThat(strip.pages).isNull()
        val bandWidth = LANDSCAPE_WIDTH * 0.28f
        assertThat(strip.viewport.left).isWithin(TOLERANCE).of(LANDSCAPE_WIDTH - bandWidth)
        assertThat(strip.viewport.top).isEqualTo(0f)
        assertThat(strip.viewport.bottom).isEqualTo(LANDSCAPE_HEIGHT)
        members.drop(1).forEachIndexed { index, member ->
            val slot = arrangement.slots.getValue(member)
            assertThat(slot.width).isWithin(TOLERANCE).of(bandWidth)
            assertThat(slot.top).isWithin(TOLERANCE).of(index * (bandWidth * 3 / 4 + SPACING))
            assertThat(slot.left).isWithin(TOLERANCE).of(strip.viewport.left)
        }
        val spotlight = arrangement.slots.getValue(members.first())
        assertThat(spotlight.left).isEqualTo(0f)
        assertThat(spotlight.right).isWithin(TOLERANCE).of(strip.viewport.left - SPACING)
        assertThat(spotlight.height).isEqualTo(LANDSCAPE_HEIGHT)
        assertThat(strip.maxScroll).isWithin(TOLERANCE).of(strip.contentLength - LANDSCAPE_HEIGHT)
    }

    /**
     * What is on screen follows the scroll: the spotlight always, and only the strip tiles whose
     * scrolled rectangle overlaps the viewport. A tile scrolled entirely off is not visible, and
     * that is what lets its video stop.
     */
    @Test
    fun `only the strip tiles in the viewport are visible`() {
        val members = aCrowd(11)
        val arrangement = computeArrangement(members, members.first(), WIDTH, HEIGHT, SPACING, canScroll = true)
        val strip = arrangement.strip!!

        val atStart = visibleTiles(arrangement, scrollOffset = 0f)
        val atEnd = visibleTiles(arrangement, scrollOffset = strip.maxScroll)
        val midSwipe = visibleTiles(arrangement, scrollOffset = strip.pages!!.stride / 2)

        // Settled on a page, that page and nothing else: four to a page here, plus the spotlight.
        assertThat(atStart).containsExactlyElementsIn(members.take(5))
        assertThat(atEnd).containsExactly(members[0], members[9], members[10])
        // Half way through a swipe the first page's right column and the second's left column are
        // on screen, and both draw; the outer columns of each have gone past the edges.
        assertThat(midSwipe).containsExactly(members[0], members[2], members[4], members[5], members[7])
    }

    /**
     * What exists reaches one page further than what is seen: the current page and its neighbours
     * are composed, everything beyond is not, so that a page turn never shows tiles popping in and
     * a call of fifty does not carry forty-odd tiles nobody is looking at.
     */
    @Test
    fun `only the tiles within a page of the viewport are composed`() {
        val members = aCrowd(21)
        val arrangement = computeArrangement(members, members.first(), WIDTH, HEIGHT, SPACING, canScroll = true)
        val strip = arrangement.strip!!
        val stride = strip.pages!!.stride

        // Four to a page: on the first page, pages one and two exist and page three does not.
        assertThat(nearbyTiles(arrangement, scrollOffset = 0f)).containsExactlyElementsIn(members.take(9))
        // On the third page, pages two to four exist.
        assertThat(nearbyTiles(arrangement, scrollOffset = 2 * stride)).containsExactlyElementsIn(listOf(members[0]) + members.subList(5, 17))
        // Everything on screen is always composed.
        assertThat(nearbyTiles(arrangement, scrollOffset = 2 * stride)).containsAtLeastElementsIn(visibleTiles(arrangement, 2 * stride))
    }

    /** A freely scrolling column reaches one viewport either way instead of one page. */
    @Test
    fun `a sideways column composes one viewport beyond the screen`() {
        val members = aCrowd(21)
        val arrangement = computeArrangement(members, members.first(), LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT, SPACING, canScroll = true)
        val strip = arrangement.strip!!

        val nearby = nearbyTiles(arrangement, scrollOffset = 0f)

        nearby.filter { it in strip.tileIds }.forEach { member ->
            assertThat(arrangement.slots.getValue(member).top).isLessThan(strip.viewport.bottom + LANDSCAPE_HEIGHT)
        }
        assertThat(nearby).containsAtLeastElementsIn(visibleTiles(arrangement, 0f))
        assertThat(nearby).doesNotContain(members.last())
    }

    /** With no strip, everyone is visible: there is nothing to have scrolled away. */
    @Test
    fun `everyone is visible when nothing scrolls`() {
        val members = listOf("me", "them", "someone")
        val arrangement = computeArrangement(members, "them", WIDTH, HEIGHT, SPACING)

        assertThat(visibleTiles(arrangement, scrollOffset = 0f)).containsExactlyElementsIn(members)
    }

    /** An area too short to hold a spotlight and a single row of the strip keeps the grid, cramped or not. */
    @Test
    fun `too small an area for a strip and a spotlight keeps the grid`() {
        val members = aCrowd(11)
        // Portrait and tiny: the spotlight takes most of it and less than a tile's height is left.
        val width = 200f
        val height = 220f

        val arrangement = computeArrangement(members, members.first(), width, height, SPACING, canScroll = true)

        assertThat(arrangement.strip).isNull()
        assertThat(arrangement.slots).isEqualTo(computeSlots(members, members.first(), width, height, SPACING))
    }

    private fun aCrowd(count: Int) = List(count) { "member$it" }

    private infix fun Rect.overlaps(other: Rect): Boolean =
        left < other.right - TOLERANCE &&
            other.left < right - TOLERANCE &&
            top < other.bottom - TOLERANCE &&
            other.top < bottom - TOLERANCE
}

/** A portrait phone's call area, in pixels, at roughly 3x. */
private const val WIDTH = 1080f
private const val HEIGHT = 1800f

/** The same phone turned on its side. */
private const val LANDSCAPE_WIDTH = 2100f
private const val LANDSCAPE_HEIGHT = 900f

private const val SPACING = 24f

/** Slots are computed in floats and placed as rounded ints, so exact edges are not the point. */
private const val TOLERANCE = 0.5f
