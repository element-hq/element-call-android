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
import io.element.android.call.api.rtc.id.UserId
import io.element.android.call.test.A_ROOM_ID
import io.element.android.call.test.FakeElementCallController
import io.element.android.call.test.aTile
import io.element.android.call.tests.testutils.WarmUpRule
import io.element.android.call.tests.testutils.consumeItemsUntilPredicate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** What the screen makes of a DM call while it rings (feature-hq 030). */
@OptIn(ExperimentalCoroutinesApi::class)
class RingingScreenStateTest {
    @get:Rule val warmUpRule = WarmUpRule()

    private val callee = UserId("@callee:example.org")

    @Test
    fun `a ringing DM draws the callee in the remote slot`() = runTest {
        val controller = FakeElementCallController(initialState = aRingingSnapshot())

        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNavigator()) }.test {
            val state = awaitItem()
            val ringing = state.tiles.last()
            assertThat(ringing.ring).isEqualTo(CallRing.CALLING)
            assertThat(ringing.userId).isEqualTo(callee)
            assertThat(ringing.isLocal).isFalse()
            assertThat(SmallCallLayout.isFullBleed(state.tiles)).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a ring that could not be sent keeps the callee, saying so`() = runTest {
        val controller = FakeElementCallController(initialState = aRingingSnapshot(ElementCallConnection.RingNotSent(callee)))

        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNavigator()) }.test {
            assertThat(awaitItem().tiles.last().ring).isEqualTo(CallRing.NOT_SENT)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** 030 R19: no hidden hang-up while the other phone rings, and the call layout's own start at pickup. */
    @Test
    fun `the chrome stays up while ringing, and takes the layout's start at pickup`() = runTest {
        val controller = FakeElementCallController(initialState = aRingingSnapshot())

        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNavigator()) }.test {
            val initial = awaitItem()
            assertThat(initial.isStageChromeVisible).isTrue()
            // Landscape starts hidden on any other stage, and a tap would hide it.
            initial.eventSink(StageChromeEvent.StageAppeared(isLandscape = true))
            initial.eventSink(StageChromeEvent.TapStage)
            advanceTimeBy(CHROME_TAP_DELAY_MS * 2)
            runCurrent()
            expectNoEvents()

            controller.state.value = aRingingSnapshot(ElementCallConnection.Connected, aTile(A_REMOTE_MEMBER_ID))
            val pickedUp = consumeItemsUntilPredicate { it.connection == ElementCallConnection.Connected }.last()
            assertThat(pickedUp.tiles.none { it.ring != null }).isTrue()
            assertThat(pickedUp.isStageChromeVisible).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun aRingingSnapshot(
        connection: ElementCallConnection = ElementCallConnection.Calling(callee, isRinging = true),
        vararg remotes: MatrixRtcTile,
    ) = ElementCallSnapshot(
        callData = ElementCallData(roomId = A_ROOM_ID, isAudioCall = true, isStartingCall = true),
        connection = connection,
        isDm = true,
        isMicrophonePermissionGranted = true,
        roster = remotes.toList().previewRoster(),
        ownTile = aTile(A_LOCAL_MEMBER_ID),
    )

    private fun aNavigator() = object : ElementCallNavigator {
        override fun close() = Unit
        override fun minimize() = Unit
        override fun requestCameraPermission() = Unit
        override fun requestScreenCapture() = Unit
    }
}
