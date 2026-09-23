/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.id.UserId
import org.junit.Test
import org.matrix.rtc.FfiCallTile
import org.matrix.rtc.FfiStreamKind
import org.matrix.rtc.FfiTileId
import org.matrix.rtc.FfiTileRef
import org.matrix.rtc.FfiTileRoster

class TileRosterMapperTest {
    @Test
    fun `detail is joined to the order by identity, not by index`() {
        // A narrowed window: the core sends records for B and C only, so index 0 of detail is B's.
        val roster = FfiTileRoster(
            order = listOf(aRef("A"), aRef("B"), aRef("C", FfiStreamKind.SCREEN_SHARE, hero = true)),
            detail = listOf(anFfiTile("B", userId = "@b:x"), anFfiTile("C", FfiStreamKind.SCREEN_SHARE, userId = "@c:x", hero = true)),
        ).map()

        assertThat(roster.order.map { it.id.memberId }).containsExactly("A", "B", "C").inOrder()
        assertThat(roster.ranked.map { it.userId }).containsExactly(UserId("@b:x"), UserId("@c:x")).inOrder()
        assertThat(roster.detail[MatrixRtcTileId("A", MatrixRtcStreamKind.CAMERA)]).isNull()
    }

    @Test
    fun `a sharer's two tiles are told apart by kind`() {
        val roster = FfiTileRoster(
            order = listOf(aRef("A", FfiStreamKind.SCREEN_SHARE, hero = true), aRef("A")),
            detail = listOf(anFfiTile("A", FfiStreamKind.SCREEN_SHARE, hero = true), anFfiTile("A", hasVideo = false)),
        ).map()

        assertThat(roster.ranked.map { it.id.kind }).containsExactly(MatrixRtcStreamKind.SCREEN_SHARE, MatrixRtcStreamKind.CAMERA).inOrder()
        assertThat(roster.ranked.map { it.isHero }).containsExactly(true, false).inOrder()
        assertThat(roster.ranked.map { it.hasVideo }).containsExactly(true, false).inOrder()
    }

    @Test
    fun `a record the order does not name is not drawn`() {
        val roster = FfiTileRoster(order = listOf(aRef("A")), detail = listOf(anFfiTile("A"), anFfiTile("GONE"))).map()

        assertThat(roster.ranked.map { it.id.memberId }).containsExactly("A")
    }

    @Test
    fun `the hand raise time survives the crossing`() {
        val tile = anFfiTile("A", handRaisedAtMs = 1_700_000_000_123uL).map()

        assertThat(tile.handRaisedAtMs).isEqualTo(1_700_000_000_123L)
        assertThat(anFfiTile("A").map().handRaisedAtMs).isNull()
    }

    private fun aRef(memberId: String, kind: FfiStreamKind = FfiStreamKind.CAMERA, hero: Boolean = false) =
        FfiTileRef(id = FfiTileId(memberId, kind), hero = hero)

    private fun anFfiTile(
        memberId: String,
        kind: FfiStreamKind = FfiStreamKind.CAMERA,
        userId: String = "@someone:example.org",
        hero: Boolean = false,
        hasVideo: Boolean = true,
        handRaisedAtMs: ULong? = null,
    ) = FfiCallTile(
        memberId = memberId,
        kind = kind,
        userId = userId,
        deviceId = "ADEVICEID",
        hero = hero,
        hasVideo = hasVideo,
        microphoneMuted = false,
        speaking = false,
        handRaisedAtMs = handRaisedAtMs,
        reachable = true,
    )
}
