/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

/**
 * Semantics test tags on the composables a UI test has to reach by hand rather than by label: the
 * things with no text and no content description, because they are pictures.
 */
object ElementCallTestTags {
    /** The draggable tile of a minimized video call. */
    const val FLOATING_TILE = "element_call_floating_tile"

    /** One tile in the call screen's layout, by its [CallTileData.tileId]. */
    fun tile(tileId: String): String = "element_call_tile_$tileId"

    /** The hero stack's arrows, in landscape, and its "1 of n" position pill. */
    const val HERO_PREVIOUS = "element_call_hero_previous"
    const val HERO_NEXT = "element_call_hero_next"
    const val HERO_INDICATOR = "element_call_hero_indicator"

    /** The fullscreen HUD's close button, which leaves fullscreen and is not the minimise button (spec 000 R12). */
    const val EXIT_FULLSCREEN = "element_call_exit_fullscreen"
}
