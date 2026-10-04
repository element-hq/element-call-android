/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.rtc.MatrixRtcDetailWindow
import io.element.android.call.api.rtc.MatrixRtcVideoConstraints
import org.junit.Test

/**
 * The shape of what a tile reports about itself.
 *
 * The value here is small but the failure is not: a tile recomputes its size on every layout pass and
 * during promotion its rectangle changes sixty times a second, so a constraint that is not
 * de-duplicated becomes sixty messages to the SFU per animation.
 */
class VideoConstraintsTest {
    @Test
    fun `constraints compare by value, so an unchanged size is recognisable`() {
        val first = MatrixRtcVideoConstraints.live(widthPx = 320, heightPx = 240)
        val same = MatrixRtcVideoConstraints.live(widthPx = 320, heightPx = 240)
        val bigger = MatrixRtcVideoConstraints.live(widthPx = 1280, heightPx = 720)

        // The de-duplication in RustMatrixRtcMediaSession is a map lookup on this value, so value equality is
        // load-bearing rather than incidental.
        assertThat(first).isEqualTo(same)
        assertThat(first).isNotEqualTo(bigger)
    }

    @Test
    fun `a paused tile keeps its stream and asks for no size`() {
        val paused = MatrixRtcVideoConstraints.Paused

        assertThat(paused.isEnabled).isTrue()
        assertThat(paused.isVisible).isFalse()
        assertThat(paused.widthPx).isEqualTo(0)
        assertThat(paused.heightPx).isEqualTo(0)
    }

    /** Released is the one demand that lets the stream go; it must not read as merely hidden. */
    @Test
    fun `a released tile is neither enabled nor visible`() {
        val released = MatrixRtcVideoConstraints.Released

        assertThat(released.isEnabled).isFalse()
        assertThat(released.isVisible).isFalse()
        assertThat(released).isNotEqualTo(MatrixRtcVideoConstraints.Paused)
    }

    @Test
    fun `a window's rank range maps to the FFI's offset and length`() {
        assertThat(MatrixRtcDetailWindow(ranks = 4 until 12).rankRange()).isEqualTo(4u to 8u)
        assertThat(MatrixRtcDetailWindow(ranks = 0 until 8).rankRange()).isEqualTo(0u to 8u)
        assertThat(MatrixRtcDetailWindow(ranks = IntRange.EMPTY).rankRange()).isEqualTo(0u to 0u)
    }
}
