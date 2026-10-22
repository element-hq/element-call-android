/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api

import kotlin.math.roundToInt

/**
 * A rectangle in the host Activity's window, in pixels: where something the call draws sits on screen.
 *
 * Immutable, unlike `android.graphics.Rect`, because it travels through a [kotlinx.coroutines.flow.StateFlow].
 */
data class ElementCallWindowRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    /**
     * This tile grown to the largest rectangle of its shape that fits a window of [windowWidth] by [windowHeight],
     * centred on it as far as the window's edges allow: what the system shrinks into picture-in-picture. Null for
     * a shape no floating window can take, or a tile not all on screen, which the system would ignore.
     */
    fun grownToFill(windowWidth: Int, windowHeight: Int): ElementCallWindowRect? {
        if (!hasPictureInPictureShape() || !isWithin(windowWidth, windowHeight)) return null
        val scale = minOf(windowWidth.toFloat() / width, windowHeight.toFloat() / height)
        val grownWidth = (width * scale).roundToInt().coerceAtMost(windowWidth)
        val grownHeight = (height * scale).roundToInt().coerceAtMost(windowHeight)
        val grownLeft = ((left + right - grownWidth) / 2).coerceIn(0, windowWidth - grownWidth)
        val grownTop = ((top + bottom - grownHeight) / 2).coerceIn(0, windowHeight - grownHeight)
        return ElementCallWindowRect(left = grownLeft, top = grownTop, right = grownLeft + grownWidth, bottom = grownTop + grownHeight)
    }

    /** Within the platform's default bounds on a window's shape, 100:239 to 239:100, which it does not expose. */
    private fun hasPictureInPictureShape(): Boolean {
        if (width <= 0 || height <= 0) return false
        return width.toLong() * 100 <= height.toLong() * 239 && width.toLong() * 239 >= height.toLong() * 100
    }

    private fun isWithin(windowWidth: Int, windowHeight: Int): Boolean =
        left >= 0 && top >= 0 && right <= windowWidth && bottom <= windowHeight
}
