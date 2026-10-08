/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

/** The layout of a call of at most five tiles, ours included (spec 019). */
internal object SmallCallLayout {
    /**
     * Every remote tile this layout can hold: at or below it the core publishes join order, the
     * same on every device, which is the arrival order the layout places by (019 R7).
     */
    const val RANKING_THRESHOLD = 4
}
