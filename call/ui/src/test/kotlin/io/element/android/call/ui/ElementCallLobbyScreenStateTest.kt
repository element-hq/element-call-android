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
import io.element.android.call.api.ElementCallLobbyJoinLabel
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.rtc.MatrixRtcCallIntent
import io.element.android.call.test.A_ROOM_ID
import io.element.android.call.test.A_USER_ID
import io.element.android.call.test.FakeElementCallController
import io.element.android.call.test.aCameraParticipant
import io.element.android.call.tests.testutils.WarmUpRule
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** What the screen makes of the lobby's snapshot (feature-hq 045). The lobby itself is `CallLobbyTest`'s, in call/impl. */
class ElementCallLobbyScreenStateTest {
    @get:Rule val warmUpRule = WarmUpRule()

    @Test
    fun `a loading lobby draws nothing it has not read, whatever else the snapshot says`() {
        val loading = aLobbySnapshot(memberCount = null).copy(isCameraEnabled = true, isMicrophonePermissionRefused = true)

        assertThat(loading.lobbyState(canRequestMicrophonePermission = true)).isEqualTo(ElementCallLobbyState.Loading)
    }

    @Test
    fun `the label follows the count, and an emptied call says it ended`() {
        fun labelOf(count: Int, hasSeenMembers: Boolean) = aLobbySnapshot(memberCount = count, hasSeenMembers = hasSeenMembers).ready()

        assertThat(labelOf(0, hasSeenMembers = false).joinLabel).isEqualTo(ElementCallLobbyJoinLabel.START)
        assertThat(labelOf(3, hasSeenMembers = true).joinLabel).isEqualTo(ElementCallLobbyJoinLabel.JOIN)
        assertThat(labelOf(3, hasSeenMembers = true).memberCount).isEqualTo(3)
        val ended = labelOf(0, hasSeenMembers = true)
        assertThat(ended.joinLabel).isEqualTo(ElementCallLobbyJoinLabel.JOIN_ANYWAY)
        assertThat(ended.isCallEndedNoticeShown).isTrue()
        assertThat(labelOf(0, hasSeenMembers = false).isCallEndedNoticeShown).isFalse()
    }

    @Test
    fun `a refused microphone disables Join with its reason, a muted one does not`() {
        val refused = aLobbySnapshot().copy(isMicrophonePermissionGranted = false, isMicrophonePermissionRefused = true)
        val muted = aLobbySnapshot().copy(isMicrophoneMuted = true)

        assertThat(refused.ready().joinUnavailableReason).isEqualTo(ElementCallLobbyJoinBlock.MICROPHONE_REFUSED)
        assertThat(refused.ready(canRequestMicrophonePermission = true).microphoneAction).isEqualTo(ElementCallLobbyMicrophoneAction.ALLOW)
        assertThat(refused.ready(canRequestMicrophonePermission = false).microphoneAction).isEqualTo(ElementCallLobbyMicrophoneAction.OPEN_SETTINGS)
        assertThat(muted.ready().joinUnavailableReason).isNull()
        assertThat(muted.ready().microphoneAction).isNull()
    }

    @Test
    fun `the preview says whether our camera is on, off or not allowed`() {
        assertThat(aLobbySnapshot().copy(isCameraEnabled = true).ready().camera).isEqualTo(ElementCallLobbyCamera.ON)
        assertThat(aLobbySnapshot().copy(isCameraEnabled = false).ready().camera).isEqualTo(ElementCallLobbyCamera.OFF)
        val refused = aLobbySnapshot().copy(isCameraEnabled = false, isCameraPermissionGranted = false, isCameraPermissionRefused = true)
        assertThat(refused.ready().camera).isEqualTo(ElementCallLobbyCamera.NOT_ALLOWED)
        assertThat(refused.callTiles().single().lobbyCamera).isEqualTo(ElementCallLobbyCamera.NOT_ALLOWED)
    }

    @Test
    fun `our tile keeps its identity and its frames from the lobby into the call`() = runTest {
        val lobby = aLobbySnapshot().copy(isCameraEnabled = true)
        val controller = FakeElementCallController(initialState = lobby)

        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNavigator()) }.test {
            val inLobby = awaitItem()
            controller.state.value = lobby.copy(connection = ElementCallConnection.Joining)
            val joining = awaitItem()
            val own = listOf(aCameraParticipant("6ffd927ac275815176463d6fee57ed62", isLocal = true, isCameraMuted = false)).previewOwnTile()
            controller.state.value = lobby.copy(connection = ElementCallConnection.Connected, ownTile = own)
            val connected = expectMostRecentItem()

            listOf(inLobby, joining, connected).forEach { state ->
                assertThat(state.tiles.single { it.isLocal }.tileId).isEqualTo(OWN_CAMERA_TILE_ID)
                assertThat(state.videoFrames[OWN_CAMERA_TILE_ID]).isSameInstanceAs(controller.ownCameraFrames)
            }
            assertThat(inLobby.lobby).isNotNull()
            assertThat(joining.lobby).isNull()
            assertThat(joining.isJoiningFromLobby).isTrue()
            assertThat(connected.isJoiningFromLobby).isFalse()
        }
    }

    @Test
    fun `the preview mirrors the front camera only, the same way in the call`() {
        fun mirrored(isFrontCamera: Boolean, connection: ElementCallConnection) =
            aLobbySnapshot().copy(isCameraEnabled = true, isFrontCamera = isFrontCamera, connection = connection).callTiles().single().isVideoMirrored

        assertThat(mirrored(isFrontCamera = true, connection = ElementCallConnection.Lobby(2, hasSeenMembers = true))).isTrue()
        assertThat(mirrored(isFrontCamera = true, connection = ElementCallConnection.Joining)).isTrue()
        assertThat(mirrored(isFrontCamera = false, connection = ElementCallConnection.Lobby(2, hasSeenMembers = true))).isFalse()
        assertThat(mirrored(isFrontCamera = false, connection = ElementCallConnection.Joining)).isFalse()
    }

    @Test
    fun `the chrome stays up until the call is on the stage, and a tap on the stage does nothing`() = runTest {
        val controller = FakeElementCallController(initialState = aLobbySnapshot())

        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNavigator()) }.test {
            val state = awaitItem()
            assertThat(state.isStageChromeVisible).isTrue()
            state.eventSink(StageChromeEvent.TapStage)
            advanceTimeBy(1.seconds)
            expectNoEvents()
        }
    }

    @Test
    fun `Join joins once the microphone is granted, and asks for it before`() = runTest {
        var microphoneRequests = 0
        val controller = FakeElementCallController(initialState = aLobbySnapshot().copy(isMicrophonePermissionGranted = false))

        moleculeFlow(
            RecompositionMode.Immediate
        ) { rememberElementCallScreenState(controller, aNavigator(onRequestMicrophone = { microphoneRequests++ })) }.test {
            awaitItem().eventSink(ElementCallScreenEvent.Join)
            assertThat(microphoneRequests).isEqualTo(1)
            assertThat(controller.joinCount).isEqualTo(0)

            controller.state.value = aLobbySnapshot()
            expectMostRecentItem().eventSink(ElementCallScreenEvent.Join)
            assertThat(controller.joinCount).isEqualTo(1)
        }
    }

    @Test
    fun `a call opened without the lobby has no lobby state and no provisional tile`() {
        val skipped = aLobbySnapshot().copy(
            callData = ElementCallData(roomId = A_ROOM_ID, intent = MatrixRtcCallIntent.VIDEO, isStartingCall = true, isDm = false, skipsLobby = true),
            connection = ElementCallConnection.Joining,
        )

        assertThat(skipped.lobbyState(canRequestMicrophonePermission = false)).isNull()
        assertThat(skipped.callTiles()).isEmpty()
    }

    private fun ElementCallSnapshot.ready(canRequestMicrophonePermission: Boolean = false) =
        lobbyState(canRequestMicrophonePermission) as ElementCallLobbyState.Ready

    private fun aLobbySnapshot(memberCount: Int? = 2, hasSeenMembers: Boolean = memberCount != null && memberCount > 0) = ElementCallSnapshot(
        callData = ElementCallData(roomId = A_ROOM_ID, intent = MatrixRtcCallIntent.VIDEO, isStartingCall = true, isDm = false),
        connection = ElementCallConnection.Lobby(memberCount = memberCount, hasSeenMembers = hasSeenMembers),
        ownUserId = A_USER_ID,
        settledIntent = MatrixRtcCallIntent.VIDEO.takeIf { memberCount != null },
        isMicrophonePermissionGranted = true,
        isCameraPermissionGranted = true,
    )

    private fun aNavigator(onRequestMicrophone: () -> Unit = {}) = object : ElementCallNavigator {
        override fun close() = Unit
        override fun minimize() = Unit
        override fun requestCameraPermission() = Unit
        override fun openAppSettings() = Unit
        override fun requestScreenCapture() = Unit
        override fun requestMicrophonePermission() = onRequestMicrophone()
    }
}
