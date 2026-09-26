/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc

/**
 * What a receiver needs of one video stream: whether it is wanted at all, whether it is on screen,
 * and how big it is drawn.
 *
 * Three demands, matching the core's: [live] is drawn at a size, [Paused] is subscribed but sent
 * nothing and resumes at once, [Released] is let go as fully as the transport supports and costs a
 * renegotiation to resume. The layout pauses what has scrolled off and releases what is beyond
 * reach, so bandwidth scales with the screen rather than with the call.
 *
 * Expressed as the size actually being drawn rather than as a quality band, because the size is
 * something the layout knows exactly and a band is a guess about it. The SFU picks the simulcast
 * layer; our job is only to stop claiming we need the largest one for a thumbnail.
 *
 * @param isEnabled whether the stream is wanted at all. False releases it.
 * @param isVisible whether the tile is on screen. False pauses the sender; the subscription stays.
 * @param widthPx the width the tile is drawn at, in pixels. Zero when [isVisible] is false.
 * @param heightPx the height the tile is drawn at, in pixels.
 */
data class MatrixRtcVideoConstraints(
    val isEnabled: Boolean,
    val isVisible: Boolean,
    val widthPx: Int,
    val heightPx: Int,
) {
    companion object {
        /** Drawn at this size. */
        fun live(widthPx: Int, heightPx: Int) = MatrixRtcVideoConstraints(isEnabled = true, isVisible = true, widthPx = widthPx, heightPx = heightPx)

        /** Subscribed but not being drawn: the sender stops, and resuming is instant. */
        val Paused = MatrixRtcVideoConstraints(isEnabled = true, isVisible = false, widthPx = 0, heightPx = 0)

        /** Not wanted: released as far as the transport allows, and a resume renegotiates. */
        val Released = MatrixRtcVideoConstraints(isEnabled = false, isVisible = false, widthPx = 0, heightPx = 0)
    }
}
