/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallData
import io.element.android.call.api.ElementCallFailure
import io.element.android.call.api.ElementCallOptions
import io.element.android.call.api.ElementCallPreferences
import io.element.android.call.api.ElementCallRoomContext
import io.element.android.call.api.rtc.MatrixRtcCallEvent
import io.element.android.call.api.rtc.MatrixRtcJoinException
import io.element.android.call.api.rtc.MatrixRtcLocalState
import io.element.android.call.api.rtc.MatrixRtcMembershipFormat
import io.element.android.call.api.rtc.MatrixRtcVideoCodec
import io.element.android.call.api.rtc.id.UserId
import io.element.android.call.test.A_ROOM_ID
import io.element.android.call.test.A_USER_ID
import io.element.android.call.test.FakeElementCallLifecycleListener
import io.element.android.call.test.FakeElementCallPreferencesStore
import io.element.android.call.test.FakeElementCallRoomContextProvider
import io.element.android.call.test.FakeMatrixRtcClient
import io.element.android.call.test.aRoster
import io.element.android.call.test.aTile
import io.element.android.call.test.audio.FakeAudioFocus
import io.element.android.call.test.audio.FakeCallAudioDeviceController
import io.element.android.call.tests.testutils.WarmUpRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The settings of feature-hq 043 as the call uses them: the two reaction switches, and the format and the codec read at the
 * join. The real controller over the fakes, as `DefaultElementCallControllerTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallSettingsTest {
    @get:Rule val warmUpRule = WarmUpRule()

    @Test
    fun `nothing changed is reactions with their sound, room state and Auto`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)
        assertThat(controller.preferences.value).isEqualTo(ElementCallPreferences.DEFAULT)
        assertThat(ElementCallPreferences.DEFAULT.showsReactions).isTrue()
        assertThat(ElementCallPreferences.DEFAULT.playsReactionSounds).isTrue()
        assertThat(ElementCallPreferences.DEFAULT.membershipFormat).isEqualTo(MatrixRtcMembershipFormat.ROOM_STATE)
        assertThat(ElementCallPreferences.DEFAULT.videoCodec).isEqualTo(MatrixRtcVideoCodec.AUTO)

        joinFully(controller)

        assertThat(rtcClient.lastMembershipFormat).isEqualTo(MatrixRtcMembershipFormat.ROOM_STATE)
        assertThat(rtcClient.lastCall?.lastVideoCodec).isEqualTo(MatrixRtcVideoCodec.AUTO)
    }

    @Test
    fun `the codecs offered are Auto, then the core's in its order`() = runTest {
        val rtcClient = FakeMatrixRtcClient(videoCodecs = listOf(MatrixRtcVideoCodec.H264, MatrixRtcVideoCodec.VP8))
        val controller = createController(rtcClient = rtcClient)
        assertThat(controller.videoCodecs).containsExactly(MatrixRtcVideoCodec.AUTO, MatrixRtcVideoCodec.H264, MatrixRtcVideoCodec.VP8).inOrder()
    }

    @Test
    fun `a format changed during a call waits for the next join`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)
        joinFully(controller)
        assertThat(rtcClient.roomCount).isEqualTo(1)

        controller.setMembershipFormat(MatrixRtcMembershipFormat.MSC)
        runCurrent()
        assertThat(rtcClient.roomCount).isEqualTo(1)
        assertThat(rtcClient.lastMembershipFormat).isEqualTo(MatrixRtcMembershipFormat.ROOM_STATE)
        // The snapshot keeps the format the call joined with, whatever the store now says (R18).
        assertThat(controller.state.value?.joiningFormat).isEqualTo(MatrixRtcMembershipFormat.ROOM_STATE)
        assertThat(controller.preferences.value.membershipFormat).isEqualTo(MatrixRtcMembershipFormat.MSC)

        controller.hangUp()
        runCurrent()
        controller.startCall(ElementCallData(roomId = A_ROOM_ID, isAudioCall = true, isStartingCall = true))
        joinFully(controller)
        assertThat(rtcClient.roomCount).isEqualTo(2)
        assertThat(rtcClient.lastMembershipFormat).isEqualTo(MatrixRtcMembershipFormat.MSC)
        assertThat(controller.state.value?.joiningFormat).isEqualTo(MatrixRtcMembershipFormat.MSC)
    }

    @Test
    fun `a format and a codec changed while joining wait for the next join`() = runTest {
        val joinGate = CompletableDeferred<Unit>()
        val rtcClient = FakeMatrixRtcClient(joinGate = joinGate)
        val controller = createController(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        controller.setMembershipFormat(MatrixRtcMembershipFormat.MSC)
        controller.setVideoCodec(MatrixRtcVideoCodec.VP9)
        joinGate.complete(Unit)
        runCurrent()

        assertThat(rtcClient.lastMembershipFormat).isEqualTo(MatrixRtcMembershipFormat.ROOM_STATE)
        assertThat(rtcClient.lastCall?.lastVideoCodec).isEqualTo(MatrixRtcVideoCodec.AUTO)
    }

    @Test
    fun `the codec read at the join is the one media connects with, and a change waits for the next join`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val store = FakeElementCallPreferencesStore(videoCodec = MatrixRtcVideoCodec.H264)
        val controller = createController(rtcClient = rtcClient, preferencesStore = store)
        joinFully(controller)
        assertThat(rtcClient.lastCall?.lastVideoCodec).isEqualTo(MatrixRtcVideoCodec.H264)

        controller.setVideoCodec(MatrixRtcVideoCodec.VP9)
        controller.setCameraEnabled(false)
        controller.setCameraEnabled(true)
        runCurrent()
        assertThat(rtcClient.lastCall?.connectMediaCount).isEqualTo(1)
        assertThat(rtcClient.lastCall?.lastVideoCodec).isEqualTo(MatrixRtcVideoCodec.H264)

        controller.hangUp()
        runCurrent()
        controller.startCall(ElementCallData(roomId = A_ROOM_ID, isAudioCall = true, isStartingCall = true))
        joinFully(controller)
        assertThat(rtcClient.lastCall?.lastVideoCodec).isEqualTo(MatrixRtcVideoCodec.VP9)
    }

    @Test
    fun `a stored codec this device cannot encode joins as Auto and stays stored`() = runTest {
        val rtcClient = FakeMatrixRtcClient(videoCodecs = listOf(MatrixRtcVideoCodec.VP8))
        val store = FakeElementCallPreferencesStore(videoCodec = MatrixRtcVideoCodec.AV1)
        val controller = createController(rtcClient = rtcClient, preferencesStore = store)
        joinFully(controller)

        assertThat(rtcClient.lastCall?.lastVideoCodec).isEqualTo(MatrixRtcVideoCodec.AUTO)
        assertThat(store.videoCodec).isEqualTo(MatrixRtcVideoCodec.AV1)
        assertThat(controller.preferences.value.videoCodec).isEqualTo(MatrixRtcVideoCodec.AV1)
    }

    @Test
    fun `the sound switch is kept while reactions are hidden, and comes back when they are shown`() = runTest {
        val store = FakeElementCallPreferencesStore()
        val controller = createController(preferencesStore = store)

        controller.setShowsReactions(false)
        controller.setPlaysReactionSounds(false)
        assertThat(store.playsReactionSounds).isFalse()
        assertThat(controller.preferences.value.playsReactionSounds).isFalse()

        controller.setShowsReactions(true)
        assertThat(controller.preferences.value.showsReactions).isTrue()
        assertThat(controller.preferences.value.playsReactionSounds).isFalse()
    }

    @Test
    fun `hidden reactions play no sound whatever the sound switch holds`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val platform = FakeElementCallPlatform(reactionSpread = 0.seconds)
        val store = FakeElementCallPreferencesStore(showsReactions = false, playsReactionSounds = true)
        val controller = createController(rtcClient = rtcClient, platform = platform, preferencesStore = store)
        joinFully(controller)
        val session = rtcClient.lastCall?.lastMediaSession!!
        session.localState.value = MatrixRtcLocalState(tile = aTile(session.localMemberId, userId = A_USER_ID), isScreenSharing = false)
        session.tiles.value = aRoster(aTile(A_REMOTE_MEMBER_ID, userId = UserId("@carol:example.org")))
        runCurrent()

        session.emit(MatrixRtcCallEvent.Reaction(A_REMOTE_MEMBER_ID, emoji = "🎉", name = "party", sound = "party"))
        advanceTimeBy(1.seconds)
        assertThat(platform.soundEffectRenderers.single().reactions).isEmpty()

        controller.setShowsReactions(true)
        session.emit(MatrixRtcCallEvent.Reaction(A_REMOTE_MEMBER_ID, emoji = "🎉", name = "party", sound = "party"))
        advanceTimeBy(1.seconds)
        assertThat(platform.soundEffectRenderers.single().reactions).containsExactly("party")
    }

    @Test
    fun `a failed MSC join keeps the format it joined with, which the screen judges it by`() = runTest {
        val rtcClient = FakeMatrixRtcClient(joinResult = { Result.failure(MatrixRtcJoinException.NoLiveKitTransport("m.livekit")) })
        val store = FakeElementCallPreferencesStore(membershipFormat = MatrixRtcMembershipFormat.MSC)
        val controller = createController(rtcClient = rtcClient, preferencesStore = store)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        val connection = controller.state.value?.connection
        assertThat(connection).isInstanceOf(ElementCallConnection.Failed::class.java)
        assertThat((connection as ElementCallConnection.Failed).failure).isInstanceOf(ElementCallFailure.Other::class.java)
        assertThat(controller.state.value?.joiningFormat).isEqualTo(MatrixRtcMembershipFormat.MSC)

        // R18a, from the failure screen and without developer mode: saved, and nothing falls back on its own before that.
        assertThat(store.membershipFormat).isEqualTo(MatrixRtcMembershipFormat.MSC)
        controller.setMembershipFormat(MatrixRtcMembershipFormat.ROOM_STATE)
        assertThat(store.membershipFormat).isEqualTo(MatrixRtcMembershipFormat.ROOM_STATE)
        assertThat(controller.state.value?.joiningFormat).isEqualTo(MatrixRtcMembershipFormat.MSC)
    }

    @Test
    fun `the format and the codec are not limited to developer mode, tile stats are`() = runTest {
        val store = FakeElementCallPreferencesStore()
        val controller = createController(preferencesStore = store, isDeveloperModeEnabled = false)

        controller.setMembershipFormat(MatrixRtcMembershipFormat.MSC)
        controller.setVideoCodec(MatrixRtcVideoCodec.VP8)
        controller.toggleTileStats()

        assertThat(store.membershipFormat).isEqualTo(MatrixRtcMembershipFormat.MSC)
        assertThat(store.videoCodec).isEqualTo(MatrixRtcVideoCodec.VP8)
        assertThat(controller.state.value?.isTileStatsVisible).isFalse()
    }

    @Test
    fun `a format the host writes to its store is what the next join uses and the sheet shows`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val store = FakeElementCallPreferencesStore()
        val controller = createController(rtcClient = rtcClient, preferencesStore = store)
        joinFully(controller)
        controller.hangUp()
        runCurrent()

        // The sample's Lab writes a scanned code's format straight to its store, not through the controller.
        store.membershipFormat = MatrixRtcMembershipFormat.MSC
        store.videoCodec = MatrixRtcVideoCodec.VP8
        controller.startCall(ElementCallData(roomId = A_ROOM_ID, isAudioCall = true, isStartingCall = true))
        joinFully(controller)

        assertThat(rtcClient.lastMembershipFormat).isEqualTo(MatrixRtcMembershipFormat.MSC)
        assertThat(controller.preferences.value.membershipFormat).isEqualTo(MatrixRtcMembershipFormat.MSC)
        assertThat(controller.preferences.value.videoCodec).isEqualTo(MatrixRtcVideoCodec.VP8)
    }

    private fun TestScope.joinFully(controller: DefaultElementCallController) {
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Connected)
    }

    private fun TestScope.createController(
        rtcClient: FakeMatrixRtcClient = FakeMatrixRtcClient(),
        platform: FakeElementCallPlatform = FakeElementCallPlatform(),
        preferencesStore: FakeElementCallPreferencesStore = FakeElementCallPreferencesStore(),
        isDeveloperModeEnabled: Boolean = true,
    ): DefaultElementCallController {
        val controller = DefaultElementCallController(
            scope = backgroundScope,
            platform = platform,
            rtcClient = rtcClient,
            audioDeviceController = FakeCallAudioDeviceController(),
            audioFocus = FakeAudioFocus(requestAudioFocusResult = {}, releaseAudioFocusResult = {}),
            lifecycleListener = FakeElementCallLifecycleListener(),
            roomContextProvider = FakeElementCallRoomContextProvider(
                roomContext = ElementCallRoomContext(displayName = "A room", isDm = false, members = emptyMap()),
            ),
            options = ElementCallOptions(isDeveloperModeEnabled = isDeveloperModeEnabled),
            preferencesStore = preferencesStore,
        )
        controller.startCall(ElementCallData(roomId = A_ROOM_ID, isAudioCall = true, isStartingCall = true))
        runCurrent()
        return controller
    }

    private companion object {
        const val A_REMOTE_MEMBER_ID = "2921a8e353fbc938be76f5b2f4946178"
    }
}
