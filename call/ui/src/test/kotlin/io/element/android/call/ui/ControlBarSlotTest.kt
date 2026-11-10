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
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** The bar's fifth control (feature-hq 009 R17, R33): what the call offers beyond its four, and how it is shown. */
class ControlBarSlotTest {
    @get:Rule val warmUpRule = WarmUpRule()

    @Test
    fun `a group call offers the hand and reactions behind More, and the share after them`() = runTest {
        val group = aSlotSnapshot(aTile(A_LOCAL_MEMBER_ID), aTile(A_REMOTE_MEMBER_ID), aTile(ANOTHER_MEMBER_ID))
        assertThat(group.extraActions()).containsExactly(ElementCallBarAction.RAISED_HAND, ElementCallBarAction.REACTIONS).inOrder()
        assertThat(group.barSlot()).isEqualTo(ElementCallBarSlot.More)

        assertThat(group.copy(isScreenShareAvailable = true).extraActions()).containsExactly(
            ElementCallBarAction.RAISED_HAND,
            ElementCallBarAction.REACTIONS,
            ElementCallBarAction.SCREEN_SHARE,
        ).inOrder()
    }

    @Test
    fun `one to one there is no slot, and a share the host enables takes it alone`() = runTest {
        val oneToOne = aSlotSnapshot(aTile(A_LOCAL_MEMBER_ID), aTile(A_REMOTE_MEMBER_ID))
        assertThat(oneToOne.extraActions()).isEmpty()
        assertThat(oneToOne.barSlot()).isNull()

        assertThat(oneToOne.copy(isScreenShareAvailable = true).barSlot()).isEqualTo(ElementCallBarSlot.Action(ElementCallBarAction.SCREEN_SHARE))
    }

    @Test
    fun `a hand still up one to one is the slot alone, so it can come down, and reactions are not offered`() = runTest {
        val oneToOne = aSlotSnapshot(aTile(A_LOCAL_MEMBER_ID, handPosition = 1), aTile(A_REMOTE_MEMBER_ID))
        assertThat(oneToOne.barSlot()).isEqualTo(ElementCallBarSlot.Action(ElementCallBarAction.RAISED_HAND))
    }

    @Test
    fun `a share is not a person`() = runTest {
        val sharing = aSlotSnapshot(aTile(A_LOCAL_MEMBER_ID), aTile(A_REMOTE_MEMBER_ID, MatrixRtcTileKind.SCREEN_SHARE), aTile(A_REMOTE_MEMBER_ID))
        assertThat(sharing.barSlot()).isNull()
    }

    private suspend fun ElementCallSnapshot.extraActions() = presented().extraActions

    private suspend fun ElementCallSnapshot.barSlot() = presented().barSlot

    private suspend fun ElementCallSnapshot.presented(): ElementCallScreenState {
        val controller = FakeElementCallController(initialState = this)
        var state: ElementCallScreenState? = null
        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNullNavigator()) }.test {
            state = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        return checkNotNull(state)
    }

    private fun aSlotSnapshot(own: MatrixRtcTile, vararg remotes: MatrixRtcTile) = ElementCallSnapshot(
        callData = ElementCallData(roomId = A_ROOM_ID, isAudioCall = true, isStartingCall = true),
        connection = ElementCallConnection.Connected,
        isMicrophonePermissionGranted = true,
        roster = remotes.toList().previewRoster(),
        ownTile = own,
    )
}

internal const val ANOTHER_MEMBER_ID = "3d7f0c7c6f2a4d0b9e8a1c5b7d6e4f21"

internal fun aNullNavigator(onMinimize: () -> Unit = {}) = object : ElementCallNavigator {
    override fun close() = Unit
    override fun minimize() = onMinimize()
    override fun requestCameraPermission() = Unit

    override fun openAppSettings() = Unit
    override fun requestScreenCapture() = Unit
}
