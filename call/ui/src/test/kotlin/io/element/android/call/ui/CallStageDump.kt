/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import io.element.android.call.api.rtc.MatrixRtcStreamRef
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.MatrixRtcTileKind
import io.element.android.call.api.rtc.MatrixRtcTileRef
import io.element.android.call.api.rtc.MatrixRtcTileRoster
import io.element.android.call.api.rtc.MatrixRtcVideoConstraints
import io.element.android.call.test.FakeMatrixRtcCall
import io.element.android.call.test.scenario.MatrixRtcScenario
import kotlin.math.roundToInt

/**
 * One frame as text, in the format `plans/003.call_layout/scenarios/README.md` asks every platform
 * for: the same dump from iOS for the same scenario is the parity check.
 */
internal object CallStageDump {
    fun frame(
        timeMs: Long,
        source: String,
        hooks: CallStageTestHooks,
        roster: MatrixRtcTileRoster,
        call: FakeMatrixRtcCall,
    ): String {
        val layout = hooks.layout
        val offset = hooks.scrollOffset()
        val tokens = roster.order.associate { ref -> ref.id to MatrixRtcScenario.tokenOf(ref, roster.detail[ref.id]) }
        val lastConstraints = call.videoConstraints.associate { it }
        val subscribed = roster.order.count { ref -> lastConstraints[ref.streamRef()]?.isEnabled == true }
        val window = call.detailWindow
        val windowText = if (window == null) {
            "-"
        } else {
            val ranks = if (window.ranks.isEmpty()) "-" else "${window.ranks.first}..<${window.ranks.last + 1}"
            val also = window.also.joinToString(",") { tokens[it] ?: it.memberId }
            "ranks $ranks also ${also.ifEmpty { "-" }}"
        }
        val spotlight = hooks.spotlightTileId?.let { id -> roster.order.firstOrNull { it.id.tileId() == id }?.let { tokens[it.id] } } ?: "-"
        val out = StringBuilder()
        out.append("== ${formatTime(timeMs)} $source\n")
        out.append("window $windowText  composed ${call.composedTiles.lastOrNull()?.size ?: 0}  subscribed $subscribed  spotlight $spotlight  fullscreen -\n")
        out.append(row("rank", "slot", "rect", "vis", "detail", "constraints"))
        out.append(ownRow(call.localMemberId, layout, offset, hooks))
        roster.order.forEachIndexed { rank, ref ->
            val id = ref.id.tileId()
            val (slot, rect) = placement(id, layout, offset, hooks)
            val vis = visibility(id, hooks, isMounted = layout != null)
            val detail = if (ref.id in call.tiles.value.detail) "detail" else "ref"
            val constraints = lastConstraints[ref.streamRef()]?.text() ?: "-"
            out.append(row(rank.toString(), tokens.getValue(ref.id), slot, rect, vis, detail, constraints))
        }
        return out.toString()
    }

    private fun ownRow(localMemberId: String, layout: CallStageLayout?, offset: Float, hooks: CallStageTestHooks): String {
        val (slot, rect) = placement(localMemberId, layout, offset, hooks)
        return row("-", "own", slot, rect, visibility(localMemberId, hooks, isMounted = layout != null), "-", "-")
    }

    private fun placement(id: String, layout: CallStageLayout?, offset: Float, hooks: CallStageTestHooks): Pair<String, String> {
        if (layout == null) return "hidden" to "-"
        val spotlight = layout.spotlight
        if (id == hooks.spotlightTileId && spotlight != null) {
            return "spot" to rect(spotlight.left, spotlight.top + offset, spotlight.width, spotlight.height)
        }
        val tile = layout.tiles[id] ?: return "hidden" to "-"
        val position = layout.gridPositions.getValue(id)
        return "grid ${position.row},${position.column}" to rect(tile.left, tile.top, tile.width, tile.height)
    }

    private fun visibility(id: String, hooks: CallStageTestHooks, isMounted: Boolean): String = when {
        !isMounted -> "released"
        id in hooks.liveIds -> "live"
        id in hooks.composedGridIds || id == hooks.spotlightTileId -> "paused"
        else -> "released"
    }

    private fun rect(x: Float, y: Float, w: Float, h: Float) = "${x.roundToInt()},${y.roundToInt()},${w.roundToInt()},${h.roundToInt()}"

    private fun MatrixRtcVideoConstraints.text(): String {
        val size = if (isVisible) "${widthPx}x$heightPx" else "auto"
        return "${if (isEnabled) "enabled" else "disabled"} ${if (isVisible) "visible" else "hidden"} $size"
    }

    private fun MatrixRtcTileRef.streamRef() = MatrixRtcStreamRef(id.memberId, id.kind.videoStreamKind)

    private fun MatrixRtcTileId.tileId(): String = if (kind == MatrixRtcTileKind.SCREEN_SHARE) "$memberId#SCREEN_SHARE" else memberId

    private fun row(rank: String, token: String, slot: String, rect: String, vis: String, detail: String, constraints: String = ""): String {
        val columns = listOf(rank.padEnd(RANK), token.padEnd(TOKEN), slot.padEnd(SLOT), rect.padEnd(RECT), vis.padEnd(VIS), detail.padEnd(DETAIL), constraints)
        return columns.joinToString(" ").trimEnd() + "\n"
    }

    private fun row(rank: String, slot: String, rect: String, vis: String, detail: String, constraints: String): String =
        "${rank.padEnd(RANK)} ${slot.padEnd(TOKEN + SLOT + 1)} ${rect.padEnd(RECT)} ${vis.padEnd(VIS)} ${detail.padEnd(DETAIL)} $constraints\n"

    fun formatTime(timeMs: Long): String = if (timeMs % MS_PER_S == 0L) "${timeMs / MS_PER_S}s" else "${timeMs / MS_PER_S.toFloat()}s"

    private const val MS_PER_S = 1_000L
    private const val RANK = 4
    private const val TOKEN = 10
    private const val SLOT = 9
    private const val RECT = 20
    private const val VIS = 8
    private const val DETAIL = 6
}
