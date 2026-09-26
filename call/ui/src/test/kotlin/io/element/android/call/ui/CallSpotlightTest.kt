/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.id.UserId
import org.junit.Test

/** The spotlight selection as a pure function (spec 003 R2 to R9, R19 to R21, R60). */
class CallSpotlightTest {
    @Test
    fun `with no hero and at most ten remote members there is no spotlight`() {
        val tiles = listOf(own()) + (1..10).map { person("p$it", isSpeaking = it == 3) }

        // R3: the rank head is not promoted; R4: our own tile does not count.
        assertThat(CallSpotlight.choose(tiles, null, null)).isEqualTo(CallSpotlight.Choice.None)
    }

    @Test
    fun `past ten remote members the first speaker in order is spotlit, and held while nobody speaks`() {
        val eleven = listOf(own()) + (1..11).map { person("p$it") }
        assertThat(CallSpotlight.choose(eleven, null, null)).isEqualTo(CallSpotlight.Choice.None)

        val speaking = eleven.map { if (it.tileId == "p3") it.copy(isActiveSpeaker = true) else it }
        val choice = CallSpotlight.choose(speaking, null, null)
        // R6
        assertThat(choice).isEqualTo(CallSpotlight.Choice.Speaker("p3"))

        // R7: silence keeps the last speaker; it does not fall back to the head.
        assertThat(CallSpotlight.choose(eleven, null, lastSpeakerId = "p3")).isEqualTo(CallSpotlight.Choice.Speaker("p3"))
        // Unless they left.
        assertThat(CallSpotlight.choose(eleven.filterNot { it.tileId == "p3" } + person("p12"), null, "p3")).isEqualTo(CallSpotlight.Choice.None)
    }

    @Test
    fun `crossing the threshold in either direction changes the arrangement at once`() {
        val eleven = listOf(own()) + (1..11).map { person("p$it", isSpeaking = it == 1) }
        assertThat(CallSpotlight.choose(eleven, null, null)).isEqualTo(CallSpotlight.Choice.Speaker("p1"))
        // R5: ten remote, no margin.
        assertThat(CallSpotlight.choose(eleven.dropLast(1), null, "p1")).isEqualTo(CallSpotlight.Choice.None)
        assertThat(CallSpotlight.choose(eleven, null, null)).isEqualTo(CallSpotlight.Choice.Speaker("p1"))
    }

    @Test
    fun `share tiles do not count towards the threshold`() {
        // R4: nine people and two shares... but a share is a hero, so use no-hero shares is impossible;
        // count people only: ten people plus a share tile that is not a hero would still be ten.
        val tiles = listOf(own()) + (1..10).map { person("p$it", isSpeaking = it == 1) } + share("p1", isHero = false)

        assertThat(CallSpotlight.choose(tiles, null, null)).isEqualTo(CallSpotlight.Choice.None)
    }

    @Test
    fun `a hero wins over the speaker, and the held speaker is dropped`() {
        val tiles = listOf(own()) + (1..11).map { person("p$it", isSpeaking = it == 2) } + share("p5")

        // R9
        assertThat(CallSpotlight.choose(tiles, null, "p2")).isEqualTo(CallSpotlight.Choice.Hero("p5#SCREEN_SHARE"))
    }

    @Test
    fun `the shown hero is kept by identity while heroes come and go`() {
        val a = share("a")
        val m = share("m")
        val tiles = listOf(own(), a, person("b"))

        // First hero shown (R20), a hero arriving does not change it.
        assertThat(CallSpotlight.choose(tiles, null, null)).isEqualTo(CallSpotlight.Choice.Hero(a.tileId))
        assertThat(CallSpotlight.choose(listOf(own(), a, m, person("b")), a.tileId, null)).isEqualTo(CallSpotlight.Choice.Hero(a.tileId))
        // The stack reorders: still M by identity (R21).
        assertThat(CallSpotlight.choose(listOf(own(), m, a, person("b")), m.tileId, null)).isEqualTo(CallSpotlight.Choice.Hero(m.tileId))
    }

    @Test
    fun `when the shown hero leaves the next in the stack is shown, else the last`() {
        val a = share("a")
        val m = share("m")
        val z = share("z")
        val stack = listOf(a.tileId, m.tileId, z.tileId)

        // R60: M was shown and left, so Z, the one after it.
        assertThat(CallSpotlight.choose(listOf(own(), a, z), m.tileId, null, lastHeroes = stack)).isEqualTo(CallSpotlight.Choice.Hero(z.tileId))
        // Z was shown and left: nothing after it, so the last remaining.
        assertThat(CallSpotlight.choose(listOf(own(), a, m), z.tileId, null, lastHeroes = stack)).isEqualTo(CallSpotlight.Choice.Hero(m.tileId))
        // The last hero leaves: no spotlight with six people (R60, R3).
        assertThat(CallSpotlight.choose(listOf(own(), person("b")), a.tileId, null, lastHeroes = listOf(a.tileId))).isEqualTo(CallSpotlight.Choice.None)
    }

    @Test
    fun `our own tile is never the spotlight`() {
        // R2: alone, and even flagged, we are never a candidate.
        assertThat(CallSpotlight.choose(listOf(own().copy(isHero = true, isActiveSpeaker = true)), null, null)).isEqualTo(CallSpotlight.Choice.None)
    }

    private fun own() = tile("own", isLocal = true)

    private fun person(id: String, isSpeaking: Boolean = false) = tile(id, isActiveSpeaker = isSpeaking)

    private fun share(memberId: String, isHero: Boolean = true) = tile(memberId, streamKind = MatrixRtcStreamKind.SCREEN_SHARE, isHero = isHero)

    private fun tile(
        memberId: String,
        isLocal: Boolean = false,
        isActiveSpeaker: Boolean = false,
        streamKind: MatrixRtcStreamKind = MatrixRtcStreamKind.CAMERA,
        isHero: Boolean = false,
    ) = CallTileData(
        memberId = memberId,
        userId = UserId("@$memberId:example.org"),
        roomMember = null,
        isLocal = isLocal,
        isMuted = false,
        isActiveSpeaker = isActiveSpeaker,
        hasVideo = false,
        isVideoMirrored = false,
        streamKind = streamKind,
        isHero = isHero,
    )
}
