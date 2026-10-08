/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

/** Where our floating tile sits. Physical: a right-to-left locale does not mirror it (019 R18). */
enum class ElementCallOwnTileCorner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    ;

    internal val isLeft: Boolean get() = this == TOP_LEFT || this == BOTTOM_LEFT
    internal val isTop: Boolean get() = this == TOP_LEFT || this == TOP_RIGHT

    companion object {
        /** Where every call starts (019 R18). */
        val Initial = BOTTOM_RIGHT

        internal fun of(isLeft: Boolean, isTop: Boolean) = when {
            isTop && isLeft -> TOP_LEFT
            isTop -> TOP_RIGHT
            isLeft -> BOTTOM_LEFT
            else -> BOTTOM_RIGHT
        }
    }
}
