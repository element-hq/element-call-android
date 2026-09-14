/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import com.google.common.truth.Truth.assertThat
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
        val first = MatrixRtcVideoConstraints(isVisible = true, widthPx = 320, heightPx = 240)
        val same = MatrixRtcVideoConstraints(isVisible = true, widthPx = 320, heightPx = 240)
        val bigger = MatrixRtcVideoConstraints(isVisible = true, widthPx = 1280, heightPx = 720)

        // The de-duplication in RustMatrixRtcCall is a map lookup on this value, so value equality is
        // load-bearing rather than incidental.
        assertThat(first).isEqualTo(same)
        assertThat(first).isNotEqualTo(bigger)
    }

    @Test
    fun `a tile that is not drawn asks for nothing`() {
        val hidden = MatrixRtcVideoConstraints.NotVisible

        assertThat(hidden.isVisible).isFalse()
        assertThat(hidden.widthPx).isEqualTo(0)
        assertThat(hidden.heightPx).isEqualTo(0)
    }
}
