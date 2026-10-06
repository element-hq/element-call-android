/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api

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
}
