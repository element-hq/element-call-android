/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc

/**
 * What a receiver needs of one video stream: whether it is on screen, and how big it is drawn.
 *
 * Expressed as the size actually being drawn rather than as a quality band, because the size is
 * something the layout knows exactly and a band is a guess about it. The SFU picks the simulcast
 * layer; our job is only to stop claiming we need the largest one for a thumbnail.
 *
 * @param isVisible whether the tile is on screen at all. A subscriber that is still subscribed but
 * not drawing - a tile scrolled away, a call in the background - should say so rather than quietly
 * receiving frames it throws away.
 * @param widthPx the width the tile is drawn at, in pixels. Zero when [isVisible] is false.
 * @param heightPx the height the tile is drawn at, in pixels.
 */
data class MatrixRtcVideoConstraints(
    val isVisible: Boolean,
    val widthPx: Int,
    val heightPx: Int,
) {
    companion object {
        /** Subscribed but not being drawn. */
        val NotVisible = MatrixRtcVideoConstraints(isVisible = false, widthPx = 0, heightPx = 0)
    }
}
