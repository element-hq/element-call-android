/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ElementCallFloatingTileTest {
    @Test
    fun `no video keeps the default portrait tile`() {
        assertThat(floatingTileSize(null)).isEqualTo(DpSize(100.dp, 140.dp))
    }

    @Test
    fun `a landscape video makes a landscape tile`() {
        val size = floatingTileSize(IntSize(640, 480))

        assertThat(size.width).isEqualTo(140.dp)
        assertThat(size.height.value).isWithin(0.01f).of(105f)
    }

    @Test
    fun `a portrait video makes a portrait tile`() {
        val size = floatingTileSize(IntSize(480, 640))

        assertThat(size.width.value).isWithin(0.01f).of(105f)
        assertThat(size.height).isEqualTo(140.dp)
    }

    @Test
    fun `an extreme video is clamped`() {
        val size = floatingTileSize(IntSize(3000, 100))

        assertThat(size.width).isEqualTo(140.dp)
        assertThat(size.height.value).isWithin(0.01f).of(140f * 9 / 16)
    }
}
