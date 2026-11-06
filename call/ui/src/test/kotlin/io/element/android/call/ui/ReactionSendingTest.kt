/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.TurbineTestContext
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallData
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.rtc.MatrixRtcReactionKind
import io.element.android.call.api.rtc.MatrixRtcSignallingHealth
import io.element.android.call.api.rtc.MatrixRtcTile
import io.element.android.call.test.A_ROOM_ID
import io.element.android.call.test.FakeElementCallController
import io.element.android.call.test.aTile
import io.element.android.call.tests.testutils.WarmUpRule
import io.element.android.call.tests.testutils.consumeItemsUntilPredicate
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** The picker, the More sheet and the banner, as the screen makes them of a snapshot (feature-hq 009). */
class ReactionSendingTest {
    @get:Rule val warmUpRule = WarmUpRule()

    @Test
    fun `a pick sends the catalogue's name and emoji, and closes the picker`() = runTest {
        val controller = FakeElementCallController(initialState = aGroupSnapshot())
        controller.reactionCatalogue = listOf(PARTY)
        present(controller) {
            val open = openPicker(awaitItem())
            assertThat(open.reactionCatalogue).containsExactly(PARTY)

            open.eventSink(ElementCallScreenEvent.SendReaction(PARTY))

            assertThat(consumeItemsUntilPredicate { it.presentedSheet == null }.last().presentedSheet).isNull()
            assertThat(controller.sentReactions).containsExactly(PARTY)
        }
    }

    @Test
    fun `closing the picker sends nothing and changes nothing else`() = runTest {
        val controller = FakeElementCallController(initialState = aGroupSnapshot())
        present(controller) {
            val open = openPicker(awaitItem())
            open.eventSink(ElementCallScreenEvent.DismissSheet)

            val closed = consumeItemsUntilPredicate { it.presentedSheet == null }.last()
            assertThat(closed.copy(eventSink = open.eventSink)).isEqualTo(open.copy(presentedSheet = null))
            assertThat(controller.sentReactions).isEmpty()
        }
    }

    @Test
    fun `More hands over to the picker, one sheet at a time`() = runTest {
        val controller = FakeElementCallController(initialState = aGroupSnapshot())
        present(controller) {
            awaitItem().eventSink(ElementCallScreenEvent.Present(ElementCallSheet.MORE))
            val more = consumeItemsUntilPredicate { it.presentedSheet == ElementCallSheet.MORE }.last()

            more.eventSink(ElementCallScreenEvent.Present(ElementCallSheet.REACTIONS))

            assertThat(consumeItemsUntilPredicate { it.presentedSheet != ElementCallSheet.MORE }.last().presentedSheet).isEqualTo(ElementCallSheet.REACTIONS)
        }
    }

    @Test
    fun `raising from More closes it, so the banner is in view`() = runTest {
        val controller = FakeElementCallController(initialState = aGroupSnapshot())
        present(controller) {
            awaitItem().eventSink(ElementCallScreenEvent.Present(ElementCallSheet.MORE))
            consumeItemsUntilPredicate { it.presentedSheet == ElementCallSheet.MORE }.last().eventSink(ElementCallScreenEvent.ToggleRaisedHand)

            assertThat(consumeItemsUntilPredicate { it.presentedSheet == null }.last().presentedSheet).isNull()
            assertThat(controller.handRaisedCalls).containsExactly(true)
        }
    }

    @Test
    fun `the picker closes when the call ends and does not come back`() = runTest {
        val controller = FakeElementCallController(initialState = aGroupSnapshot())
        present(controller) {
            openPicker(awaitItem())
            controller.state.value = aGroupSnapshot().copy(connection = ElementCallConnection.Ended)
            assertThat(consumeItemsUntilPredicate { it.presentedSheet == null }.last().presentedSheet).isNull()

            controller.state.value = aGroupSnapshot()
            assertThat(consumeItemsUntilPredicate { it.connection == ElementCallConnection.Connected }.last().presentedSheet).isNull()
        }
    }

    @Test
    fun `the picker closes on minimise, rotation and the screen stopping`() = runTest {
        var minimizeCount = 0
        val controller = FakeElementCallController(initialState = aGroupSnapshot())
        present(controller, aNullNavigator(onMinimize = { minimizeCount++ })) {
            var current = awaitItem()
            for (event in listOf(ElementCallScreenEvent.Minimize, StageChromeEvent.StageRotated(isLandscape = true), StageChromeEvent.ScreenStopped)) {
                openPicker(current).eventSink(event)
                current = consumeItemsUntilPredicate { it.presentedSheet == null }.last()
                assertThat(current.presentedSheet).isNull()
            }
            assertThat(minimizeCount).isEqualTo(1)
        }
    }

    @Test
    fun `the picker closes when the call drops below three people`() = runTest {
        val controller = FakeElementCallController(initialState = aGroupSnapshot())
        present(controller) {
            openPicker(awaitItem())
            controller.state.value = aSnapshot(aTile(A_LOCAL_MEMBER_ID), aTile(A_REMOTE_MEMBER_ID))
            assertThat(consumeItemsUntilPredicate { it.presentedSheet == null }.last().presentedSheet).isNull()
        }
    }

    @Test
    fun `the banner shows only while our hand is up, and not under a sheet`() = runTest {
        val controller = FakeElementCallController(initialState = aGroupSnapshot())
        present(controller) {
            assertThat(awaitItem().showsRaisedHandBanner).isFalse()

            controller.state.value = aGroupSnapshot(own = aTile(A_LOCAL_MEMBER_ID, handPosition = 1))
            val up = consumeItemsUntilPredicate { it.isHandRaised }.last()
            assertThat(up.showsRaisedHandBanner).isTrue()

            up.eventSink(ElementCallScreenEvent.Present(ElementCallSheet.MORE))
            assertThat(consumeItemsUntilPredicate { it.presentedSheet != null }.last().showsRaisedHandBanner).isFalse()

            controller.state.value = aGroupSnapshot().copy(isMicrophoneMuted = true)
            assertThat(consumeItemsUntilPredicate { !it.isHandRaised }.last().showsRaisedHandBanner).isFalse()
        }
    }

    @Test
    fun `reactions are offered but not usable while our homeserver is unreachable`() = runTest {
        val controller = FakeElementCallController(initialState = aGroupSnapshot().copy(signalling = MatrixRtcSignallingHealth.Unreachable(sinceMs = 1)))
        present(controller) {
            val state = awaitItem()
            assertThat(state.extraActions).contains(ElementCallBarAction.REACTIONS)
            assertThat(state.isReactionControlEnabled).isFalse()
        }
    }

    private suspend fun present(
        controller: FakeElementCallController,
        navigator: ElementCallNavigator = aNullNavigator(),
        block: suspend TurbineTestContext<ElementCallScreenState>.() -> Unit,
    ) = moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, navigator) }.test {
        block()
        cancelAndIgnoreRemainingEvents()
    }

    private suspend fun TurbineTestContext<ElementCallScreenState>.openPicker(from: ElementCallScreenState): ElementCallScreenState {
        from.eventSink(ElementCallScreenEvent.Present(ElementCallSheet.REACTIONS))
        return consumeItemsUntilPredicate { it.presentedSheet == ElementCallSheet.REACTIONS }.last()
    }

    private fun aGroupSnapshot(own: MatrixRtcTile = aTile(A_LOCAL_MEMBER_ID)) = aSnapshot(own, aTile(A_REMOTE_MEMBER_ID), aTile(ANOTHER_MEMBER_ID))

    private fun aSnapshot(own: MatrixRtcTile, vararg remotes: MatrixRtcTile) = ElementCallSnapshot(
        callData = ElementCallData(roomId = A_ROOM_ID, isAudioCall = true, isStartingCall = true),
        connection = ElementCallConnection.Connected,
        isMicrophonePermissionGranted = true,
        roster = remotes.toList().previewRoster(),
        ownTile = own,
    )

    private companion object {
        val PARTY = MatrixRtcReactionKind(name = "party", emoji = "🎉")
    }
}
