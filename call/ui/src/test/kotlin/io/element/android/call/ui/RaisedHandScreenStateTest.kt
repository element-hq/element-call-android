/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallData
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.rtc.MatrixRtcTile
import io.element.android.call.api.rtc.MatrixRtcTileKind
import io.element.android.call.test.A_ROOM_ID
import io.element.android.call.test.FakeElementCallController
import io.element.android.call.test.aTile
import io.element.android.call.tests.testutils.WarmUpRule
import io.element.android.call.tests.testutils.consumeItemsUntilPredicate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

private const val ANOTHER_REMOTE_MEMBER_ID = "3d7f0c7c6f2a4d0b9e8a1c5b7d6e4f21"

/** What the screen makes of the hands in a snapshot (009), over [FakeElementCallController] as [ElementCallScreenStateTest] is. */
@OptIn(ExperimentalCoroutinesApi::class)
class RaisedHandScreenStateTest {
    @get:Rule val warmUpRule = WarmUpRule()

    @Test
    fun `each tile carries its hand's place in the queue, ours too, and a share none`() = runTest {
        val controller = FakeElementCallController(
            initialState = aHandSnapshot(
                own = aTile(A_LOCAL_MEMBER_ID, handPosition = 2),
                aTile(A_REMOTE_MEMBER_ID, MatrixRtcTileKind.SCREEN_SHARE),
                aTile(A_REMOTE_MEMBER_ID, handPosition = 1),
                aTile(ANOTHER_REMOTE_MEMBER_ID),
            ),
        )

        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNavigator()) }.test {
            val state = awaitItem()

            assertThat(state.tiles.map { it.handPosition }).containsExactly(2, null, 1, null).inOrder()
            assertThat(state.isHandRaised).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `one to one no hand is shown and the control is gone, unless our own hand is up`() = runTest {
        val controller = FakeElementCallController(initialState = aHandSnapshot(own = aTile(A_LOCAL_MEMBER_ID), aTile(A_REMOTE_MEMBER_ID, handPosition = 1)))

        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNavigator()) }.test {
            val oneToOne = awaitItem()
            assertThat(oneToOne.tiles.map { it.handPosition }).containsExactly(null, null)
            assertThat(oneToOne.showsRaiseHandControl).isFalse()

            controller.state.value = aHandSnapshot(own = aTile(A_LOCAL_MEMBER_ID, handPosition = 2), aTile(A_REMOTE_MEMBER_ID, handPosition = 1))
            val ownUp = consumeItemsUntilPredicate { it.isHandRaised }.last()
            assertThat(ownUp.tiles.map { it.handPosition }).containsExactly(null, null)
            // The way down stays (009 R2).
            assertThat(ownUp.showsRaiseHandControl).isTrue()

            controller.state.value = aHandSnapshot(
                own = aTile(A_LOCAL_MEMBER_ID, handPosition = 2),
                aTile(A_REMOTE_MEMBER_ID, handPosition = 1),
                aTile(ANOTHER_REMOTE_MEMBER_ID),
            )
            val three = consumeItemsUntilPredicate { it.tiles.size == 3 }.last()
            assertThat(three.tiles.map { it.handPosition }).containsExactly(2, 1, null).inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the control is offered from three people, and a share does not count as one`() = runTest {
        val controller = FakeElementCallController(
            initialState = aHandSnapshot(own = aTile(A_LOCAL_MEMBER_ID), aTile(A_REMOTE_MEMBER_ID, MatrixRtcTileKind.SCREEN_SHARE), aTile(A_REMOTE_MEMBER_ID)),
        )

        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNavigator()) }.test {
            assertThat(awaitItem().showsRaiseHandControl).isFalse()

            controller.state.value = aHandSnapshot(own = aTile(A_LOCAL_MEMBER_ID), aTile(A_REMOTE_MEMBER_ID), aTile(ANOTHER_REMOTE_MEMBER_ID))
            assertThat(consumeItemsUntilPredicate { it.showsRaiseHandControl }.last().showsRaiseHandControl).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the hand control asks for the opposite of what the homeserver has, and a failure is dismissed`() = runTest {
        val controller = FakeElementCallController(
            initialState = aHandSnapshot(own = aTile(A_LOCAL_MEMBER_ID, handPosition = 1), aTile(A_REMOTE_MEMBER_ID), aTile(ANOTHER_REMOTE_MEMBER_ID)),
        )

        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNavigator()) }.test {
            val state = awaitItem()
            state.eventSink(ElementCallScreenEvent.ToggleRaisedHand)
            state.eventSink(ElementCallScreenEvent.DismissHandRequestFailure)

            assertThat(controller.handRaisedCalls).containsExactly(false)
            assertThat(controller.clearHandRequestFailureCount).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isOneToOne and the full-bleed layout agree`() {
        val shapes = listOf(
            aHandSnapshot(own = aTile(A_LOCAL_MEMBER_ID)),
            aHandSnapshot(own = aTile(A_LOCAL_MEMBER_ID), aTile(A_REMOTE_MEMBER_ID)),
            aHandSnapshot(own = aTile(A_LOCAL_MEMBER_ID), aTile(A_REMOTE_MEMBER_ID, MatrixRtcTileKind.SCREEN_SHARE), aTile(A_REMOTE_MEMBER_ID)),
            aHandSnapshot(own = aTile(A_LOCAL_MEMBER_ID), aTile(A_REMOTE_MEMBER_ID), aTile(ANOTHER_REMOTE_MEMBER_ID)),
            aHandSnapshot(own = null, aTile(A_REMOTE_MEMBER_ID)),
        )

        shapes.forEach { snapshot ->
            assertThat(snapshot.isOneToOne).isEqualTo(SmallCallLayout.isFullBleed(snapshot.callTiles()))
        }
    }

    private fun aHandSnapshot(own: MatrixRtcTile?, vararg remotes: MatrixRtcTile) = ElementCallSnapshot(
        callData = ElementCallData(roomId = A_ROOM_ID, isAudioCall = true, isStartingCall = true),
        connection = ElementCallConnection.Connected,
        isMicrophonePermissionGranted = true,
        roster = remotes.toList().previewRoster(),
        ownTile = own,
    )

    private fun aNavigator() = object : ElementCallNavigator {
        override fun close() = Unit
        override fun requestCameraPermission() = Unit
        override fun requestScreenCapture() = Unit
    }
}
