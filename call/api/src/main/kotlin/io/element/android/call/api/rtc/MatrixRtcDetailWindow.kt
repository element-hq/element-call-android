/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc

/**
 * Which tiles the core sends full records for: a rank range over [MatrixRtcTileRoster.order], plus
 * tiles named by identity wherever they rank.
 *
 * Declared by the layout, because the layout is what knows what is drawn. The default, detail for
 * every tile, costs the whole call on every roster update; a window costs the tiles on screen.
 *
 * @param ranks positions in the order, half open at the top as an [IntRange] is not: `0 until 8`
 * is the first eight. Empty declares no range.
 * @param also tiles drawn out of rank - the spotlight, a fullscreen tile, the Picture in Picture
 * source - which a range over the grid would not cover.
 */
data class MatrixRtcDetailWindow(
    val ranks: IntRange,
    val also: Set<MatrixRtcTileId> = emptySet(),
) {
    /** Whether the tile at [rank] with [id] is inside the window. */
    fun contains(rank: Int, id: MatrixRtcTileId): Boolean = rank in ranks || id in also

    companion object {
        /** The core's default: full records for every tile. */
        val Everything = MatrixRtcDetailWindow(ranks = 0 until Int.MAX_VALUE)
    }
}

/** [MatrixRtcTileRoster.detail] narrowed to [window], joined by identity: what the core would send. */
fun MatrixRtcTileRoster.windowed(window: MatrixRtcDetailWindow): MatrixRtcTileRoster {
    val kept = order.filterIndexed { rank, ref -> window.contains(rank, ref.id) }.map { it.id }.toSet()
    return copy(detail = detail.filterKeys { it in kept })
}
