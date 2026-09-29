/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.rtc.MatrixRtcTileKind
import io.element.android.call.test.scenario.MatrixRtcScenario
import io.element.android.call.test.scenario.ScenarioAction
import io.element.android.call.test.scenario.ScenarioContent
import io.element.android.call.test.scenario.toRoster
import org.junit.Assert.assertThrows
import org.junit.Test

/** The scenario grammar, as `scenarios/README.md` in feature-hq states it. */
class ScenarioParserTest {
    @Test
    fun `a roster frame carries the whole order with its flags`() {
        val scenario = MatrixRtcScenario.parse(
            "t",
            """
            // a comment
            0s     viewport 393x734 bottom 34
            2.5s   A#* Av B! C^ Dm E   // trailing comment
            """.trimIndent(),
        )

        assertThat(scenario.frames).hasSize(2)
        assertThat(scenario.frames[0].timeMs).isEqualTo(0)
        assertThat(scenario.frames[0].content).isEqualTo(ScenarioContent.Action(ScenarioAction.Viewport(393, 734, bottomInset = 34, topInset = 0)))
        val roster = scenario.frames[1].content as ScenarioContent.Roster
        assertThat(scenario.frames[1].timeMs).isEqualTo(2_500)
        assertThat(roster.tiles.map { it.member }).containsExactly("A", "A", "B", "C", "D", "E").inOrder()
        assertThat(roster.tiles[0].isShare).isTrue()
        assertThat(roster.tiles[0].isHero).isTrue()
        assertThat(roster.tiles[1].hasVideo).isTrue()
        assertThat(roster.tiles[2].isSpeaking).isTrue()
        assertThat(roster.tiles[3].isHandRaised).isTrue()
        assertThat(roster.tiles[4].isMuted).isTrue()
        assertThat(scenario.frames[1].source).isEqualTo("2.5s   A#* Av B! C^ Dm E")
    }

    @Test
    fun `a roster maps to the core's types with the fixture ids`() {
        val roster = (MatrixRtcScenario.parse("t", "0s A#* B").frames.single().content as ScenarioContent.Roster).toRoster()

        assertThat(roster.order.map { it.id.memberId }).containsExactly("@A:example.com:DEVICE", "@B:example.com:DEVICE").inOrder()
        assertThat(roster.order[0].id.kind).isEqualTo(MatrixRtcTileKind.SCREEN_SHARE)
        assertThat(roster.order[0].isHero).isTrue()
        assertThat(roster.order[1].userId.value).isEqualTo("@b:example.com")
        assertThat(roster.detail.keys).containsExactlyElementsIn(roster.order.map { it.id })
    }

    @Test
    fun `every action parses`() {
        val actions = MatrixRtcScenario.parse(
            "t",
            """
            0s scroll 300
            1s rotate landscape
            2s fullscreen H
            3s fullscreen none
            4s swipe-hero next
            5s minimize
            6s restore
            7s me vm
            8s detail-only A B#
            9s tick
            """.trimIndent(),
        ).frames.map { (it.content as ScenarioContent.Action).action }

        assertThat(actions[0]).isEqualTo(ScenarioAction.Scroll(300f))
        assertThat(actions[1]).isEqualTo(ScenarioAction.Rotate(isLandscape = true))
        assertThat((actions[2] as ScenarioAction.Fullscreen).tile?.member).isEqualTo("H")
        assertThat(actions[3]).isEqualTo(ScenarioAction.Fullscreen(null))
        assertThat(actions[4]).isEqualTo(ScenarioAction.SwipeHero(next = true))
        assertThat(actions[5]).isEqualTo(ScenarioAction.Minimize)
        assertThat(actions[6]).isEqualTo(ScenarioAction.Restore)
        assertThat(actions[7]).isEqualTo(ScenarioAction.Me(hasVideo = true, isMuted = true))
        assertThat((actions[8] as ScenarioAction.DetailOnly).tiles.map { it.member }).containsExactly("A", "B").inOrder()
        assertThat(actions[9]).isEqualTo(ScenarioAction.Tick)
    }

    @Test
    fun `a lowercase name and a time going backwards are refused with the line`() {
        assertThat(assertThrows(IllegalArgumentException::class.java) { MatrixRtcScenario.parse("t", "0s Cv\n1s cv") }.message).contains("line 2")
        assertThat(assertThrows(IllegalArgumentException::class.java) { MatrixRtcScenario.parse("t", "2s A\n1s A") }.message).contains("line 2")
    }

    @Test
    fun `the vendored corpus parses`() {
        for (name in listOf("001_small_calls", "002_listen_mode", "003_two_shares", "004_scroll_and_rank", "005_rotation_and_fullscreen", "006_two_hundred")) {
            assertThat(MatrixRtcScenario.load(name).frames).isNotEmpty()
        }
    }
}
