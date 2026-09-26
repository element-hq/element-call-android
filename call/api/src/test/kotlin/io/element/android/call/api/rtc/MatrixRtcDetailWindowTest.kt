/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.rtc.id.UserId
import org.junit.Test

/**
 * The join the core makes when it narrows a roster to the declared window, reproduced for the fakes:
 * by rank for the range, by identity for the explicit set, never by index into detail.
 */
class MatrixRtcDetailWindowTest {
    @Test
    fun `the range keeps records by rank and the explicit set by identity`() {
        val roster = aRosterOf("a", "b", "c", "d", "e")

        val windowed = roster.windowed(MatrixRtcDetailWindow(ranks = 1 until 3, also = setOf(id("e"))))

        assertThat(windowed.order).isEqualTo(roster.order)
        assertThat(windowed.detail.keys).containsExactly(id("b"), id("c"), id("e"))
        assertThat(windowed.ranked.map { it.id.memberId }).containsExactly("b", "c", "e").inOrder()
    }

    @Test
    fun `an empty range with no explicit set keeps references only`() {
        val windowed = aRosterOf("a", "b").windowed(MatrixRtcDetailWindow(ranks = IntRange.EMPTY))

        assertThat(windowed.order).hasSize(2)
        assertThat(windowed.detail).isEmpty()
    }

    @Test
    fun `the default window keeps everything`() {
        val roster = aRosterOf("a", "b", "c")

        assertThat(roster.windowed(MatrixRtcDetailWindow.Everything)).isEqualTo(roster)
    }

    private fun id(memberId: String) = MatrixRtcTileId(memberId, MatrixRtcTileKind.PERSON)

    private fun aRosterOf(vararg memberIds: String): MatrixRtcTileRoster {
        val tiles = memberIds.map { memberId ->
            MatrixRtcTile(
                id = id(memberId),
                userId = UserId("@$memberId:example.org"),
                deviceId = null,
                isHero = false,
                hasVideo = false,
                isMicrophoneMuted = false,
                isSpeaking = false,
                handRaisedAtMs = null,
                isReachable = true,
            )
        }
        return MatrixRtcTileRoster(
            order = tiles.map { MatrixRtcTileRef(it.id, it.userId, it.isHero) },
            detail = tiles.associateBy { it.id },
        )
    }
}
