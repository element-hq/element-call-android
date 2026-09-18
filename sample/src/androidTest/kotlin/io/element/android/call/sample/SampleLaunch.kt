/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import android.content.Intent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider

/** Open the sample straight into [fixture], the way `--es fixture` on the launch intent does. */
internal fun launchSample(fixture: SampleFixture): ActivityScenario<SampleActivity> {
    val intent = Intent(ApplicationProvider.getApplicationContext(), SampleActivity::class.java)
        .putExtra(SampleActivity.EXTRA_FIXTURE, fixture.key)
    return ActivityScenario.launch(intent)
}

/**
 * How many distinct colours a sampled grid of [image] holds, quantised so that compression and
 * dithering do not count. A rendered colour-bar frame has eight; an avatar disc on a flat background
 * has two or three; a black or grey rectangle has one.
 */
internal fun ImageBitmap.distinctColours(grid: Int = COLOUR_GRID): Int {
    val pixels = toPixelMap()
    val colours = HashSet<Int>()
    for (row in 0 until grid) {
        for (column in 0 until grid) {
            val x = (column * (pixels.width - 1)) / (grid - 1)
            val y = (row * (pixels.height - 1)) / (grid - 1)
            val pixel = pixels[x, y]
            colours += ((pixel.red * COLOUR_LEVELS).toInt() shl 8) or ((pixel.green * COLOUR_LEVELS).toInt() shl 4) or (pixel.blue * COLOUR_LEVELS).toInt()
        }
    }
    return colours.size
}

private const val COLOUR_GRID = 24
private const val COLOUR_LEVELS = 7
