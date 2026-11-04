/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ElementCallReactionBubbleMotionTest {
    private val width = 400f
    private val height = 800f

    @Test
    fun `it rises fastest at first`() {
        val motion = ElementCallReactionBubbleMotion(seed = 7)
        val start = motion.frame(0.seconds, width, height).centerY
        val third = motion.frame(motion.lifetime / 3, width, height).centerY
        val twoThirds = motion.frame(motion.lifetime * 2 / 3, width, height).centerY
        val end = motion.frame(motion.lifetime, width, height).centerY

        assertThat(start - third).isGreaterThan(twoThirds - end)
        assertThat(start).isGreaterThan(height)
        assertThat(end).isLessThan(0f)
    }

    @Test
    fun `it is full size a third of the way up`() {
        val motion = ElementCallReactionBubbleMotion(seed = 7)
        val size = ElementCallReactionBubbleMotion.size(height)
        assertThat(motion.frame(0.seconds, width, height).scale).isWithin(1e-4f).of(size * ElementCallReactionBubbleMotion.START_SCALE)
        // A third of the height is reached before a third of the time, on the ease-out.
        assertThat(motion.frame(motion.lifetime / 3, width, height).scale).isWithin(1e-4f).of(size)
    }

    @Test
    fun `it is visible three seconds from arrival and gone by four`() {
        val motion = ElementCallReactionBubbleMotion(seed = 7, spread = 400.milliseconds)
        assertThat(motion.lifetime).isEqualTo(3600.milliseconds)
        assertThat(motion.frame(2599.milliseconds, width, height).alpha).isEqualTo(1f)
        assertThat(motion.frame(3100.milliseconds, width, height).alpha).isLessThan(1f)
        assertThat(motion.frame(3600.milliseconds, width, height).alpha).isEqualTo(0f)
    }

    @Test
    fun `it starts clear of the edges, the same for one seed every time`() {
        (1L..200L).forEach { seed ->
            val startX = ElementCallReactionBubbleMotion(seed).startX
            assertThat(startX).isAtLeast(0.1f)
            assertThat(startX).isAtMost(0.9f)
        }
        assertThat(ElementCallReactionBubbleMotion(42).frame(1.seconds, width, height))
            .isEqualTo(ElementCallReactionBubbleMotion(42).frame(1.seconds, width, height))
    }

    @Test
    fun `the round flexes a quarter turn out of step, by at most a tenth`() {
        val motion = ElementCallReactionBubbleMotion(seed = 3)
        val frames = (0..100).map { motion.frame((it * 30).milliseconds, width, height) }
        frames.forEach {
            assertThat(it.flexX).isWithin(0.1001f).of(1f)
            assertThat(it.flexY).isWithin(0.1001f).of(1f)
        }
        assertThat(frames.any { it.flexX > 1.05f && it.flexY < 1.05f }).isTrue()
    }

    @Test
    fun `it is smaller on a landscape stage`() {
        assertThat(ElementCallReactionBubbleMotion.size(380f)).isLessThan(ElementCallReactionBubbleMotion.size(800f))
    }
}
