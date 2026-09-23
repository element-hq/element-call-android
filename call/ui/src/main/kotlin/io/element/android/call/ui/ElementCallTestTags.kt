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

    /** One tile in the call screen's layout, by its [CallTile.tileId]. */
    fun tile(tileId: String): String = "element_call_tile_$tileId"
}
