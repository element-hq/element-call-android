/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallData
import io.element.android.call.api.ElementCallFailure
import io.element.android.call.api.ElementCallLobbyJoinLabel
import io.element.android.call.api.ElementCallOptions
import io.element.android.call.api.ElementCallPreferences
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.rtc.MatrixRtcCallIntent
import io.element.android.call.api.rtc.MatrixRtcCallKind
import io.element.android.call.api.rtc.MatrixRtcGroupNotify
import io.element.android.call.api.rtc.MatrixRtcJoinException
import io.element.android.call.api.rtc.MatrixRtcMembershipFormat
import io.element.android.call.test.A_ROOM_ID
import io.element.android.call.test.A_ROOM_ID_2
import io.element.android.call.test.A_USER_ID
import io.element.android.call.test.FakeElementCallAnalytics
import io.element.android.call.test.FakeElementCallLifecycleListener
import io.element.android.call.test.FakeElementCallPreferencesStore
import io.element.android.call.test.FakeElementCallRoomContextProvider
import io.element.android.call.test.FakeMatrixRtcClient
import io.element.android.call.test.FakeMatrixRtcRoom
import io.element.android.call.test.audio.FakeAudioFocus
import io.element.android.call.test.audio.FakeCallAudioDeviceController
import io.element.android.call.test.audio.aBluetoothHeadset
import io.element.android.call.test.audio.aSpeaker
import io.element.android.call.test.audio.anEarpiece
import io.element.android.call.tests.testutils.WarmUpRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * The lobby (feature-hq 045), on the real controller over fakes: where "nothing reaches the room" can be counted call by
 * call, which no screen test can see. What the screen draws of it is `ElementCallScreenStateTest`'s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallLobbyTest {
    @get:Rule val warmUpRule = WarmUpRule()

    private val lifecycleListener = FakeElementCallLifecycleListener()
    private val analytics = FakeElementCallAnalytics()
    private val platform = FakeElementCallPlatform()
    private var focusRequests = 0
    private var focusReleases = 0
    private val audioFocus = FakeAudioFocus(requestAudioFocusResult = { focusRequests++ }, releaseAudioFocusResult = { focusReleases++ })
    private val audioDevices = FakeCallAudioDeviceController(initialDevices = listOf(anEarpiece(), aSpeaker()))

    @Test
    fun `the lobby shows in a group room unless the host skips it, and never in a DM`() = runTest {
        fun TestScope.connectionFor(isDm: Boolean, skipsLobby: Boolean): ElementCallConnection? =
            createLobby(isDm = isDm, skipsLobby = skipsLobby).state.value?.connection

        assertThat(connectionFor(isDm = false, skipsLobby = false)).isInstanceOf(ElementCallConnection.Lobby::class.java)
        assertThat(connectionFor(isDm = false, skipsLobby = true)).isEqualTo(ElementCallConnection.RequestingPermission)
        assertThat(connectionFor(isDm = true, skipsLobby = false)).isEqualTo(ElementCallConnection.RequestingPermission)
        assertThat(connectionFor(isDm = true, skipsLobby = true)).isEqualTo(ElementCallConnection.RequestingPermission)
    }

    @Test
    fun `loading leaves in one update, with the intent, the camera and the count together`() = runTest {
        val roomGate = CompletableDeferred<Unit>()
        val rtcClient = FakeMatrixRtcClient(roomGate = roomGate, onRoomOpened = { it.memberCount.value = 2 })
        val controller = createLobby(rtcClient = rtcClient)
        val seen = mutableListOf<ElementCallSnapshot>()
        backgroundScope.launch { controller.state.collect { it?.let(seen::add) } }
        controller.setMicrophonePermissionGranted(true)
        controller.setCameraPermissionGranted(true)
        runCurrent()

        val loading = controller.state.value!!
        assertThat(loading.connection).isEqualTo(ElementCallConnection.Lobby(memberCount = null, hasSeenMembers = false))
        assertThat(loading.settledIntent).isNull()
        assertThat(loading.isCameraEnabled).isFalse()
        controller.join()
        runCurrent()
        assertThat(controller.state.value?.connection).isInstanceOf(ElementCallConnection.Lobby::class.java)
        assertThat(rtcClient.cameras.single().startCount).isEqualTo(0)

        roomGate.complete(Unit)
        runCurrent()

        val loaded = controller.state.value!!
        assertThat(loaded.connection).isEqualTo(ElementCallConnection.Lobby(memberCount = 2, hasSeenMembers = true))
        assertThat(loaded.settledIntent).isEqualTo(MatrixRtcCallIntent.VIDEO)
        assertThat(loaded.isCameraEnabled).isTrue()
        // Never a label, a camera or an intent from before the core had read the call.
        seen.filter { (it.connection as? ElementCallConnection.Lobby)?.isLoading == true }.forEach {
            assertThat(it.settledIntent).isNull()
            assertThat(it.isCameraEnabled).isFalse()
        }
        seen.filter { (it.connection as? ElementCallConnection.Lobby)?.isLoading == false }.forEach {
            assertThat(it.settledIntent).isNotNull()
        }
        assertThat(rtcClient.cameras.single().startCount).isEqualTo(1)
    }

    @Test
    fun `a call the core cannot read ends on the failure screen, reported as a failed attempt`() = runTest {
        val failures = mapOf<String, (FakeMatrixRtcRoom) -> Unit>(
            "the intent fails" to { room -> room.callIntentResult = { Result.failure(IllegalStateException("shut down")) } },
            "the count ends before its first value" to { room -> room.memberCountFlow = emptyFlow() },
        )
        failures.forEach { (case, script) ->
            val analytics = FakeElementCallAnalytics()
            val controller = createLobby(rtcClient = FakeMatrixRtcClient(onRoomOpened = script), analytics = analytics)
            assertLobbyFailed(controller, analytics, case)
        }
        val analytics = FakeElementCallAnalytics()
        val noRoom = createLobby(rtcClient = FakeMatrixRtcClient(roomResult = { Result.failure(IllegalStateException("offline")) }), analytics = analytics)
        assertLobbyFailed(noRoom, analytics, "the room does not open")
    }

    @Test
    fun `one loading timeout bounds an intent and a first count that never answer`() = runTest {
        val cases = mapOf<String, (FakeMatrixRtcRoom) -> Unit>(
            "the intent never answers" to { room -> room.callIntentResult = { awaitCancellation() } },
            "the first count never arrives" to { room -> room.memberCount.value = null },
        )
        cases.forEach { (case, script) ->
            val analytics = FakeElementCallAnalytics()
            val controller = createLobby(rtcClient = FakeMatrixRtcClient(onRoomOpened = script), analytics = analytics)
            advanceTimeBy(DefaultElementCallController.LOBBY_LOADING_TIMEOUT - 1.milliseconds)
            assertThat((controller.state.value?.connection as? ElementCallConnection.Lobby)?.isLoading).isTrue()
            advanceTimeBy(2.milliseconds)
            assertLobbyFailed(controller, analytics, case)
        }
    }

    private fun TestScope.assertLobbyFailed(controller: DefaultElementCallController, analytics: FakeElementCallAnalytics, case: String) {
        assertWithMessage(case).that(controller.state.value).isNotNull()
        runCurrent()
        val failed = controller.state.value!!
        val failure = (failed.connection as? ElementCallConnection.Failed)?.failure as? ElementCallFailure.Other
        assertThat(failure?.isJoinFailure).isTrue()
        assertThat(failed.joiningFormat).isEqualTo(ElementCallPreferences.DEFAULT.membershipFormat)
        // As a join that cannot open the room reports it (044 C7).
        assertThat(analytics.names).containsExactly("CallJoinAttempted", "CallJoinOutcome", "CallFailure").inOrder()
        controller.hangUp()
        runCurrent()
        assertThat(controller.state.value).isNull()
        // Closed as a lobby: nothing was started, so nothing is ended.
        assertThat(lifecycleListener.endedCalls).isEmpty()
        assertThat(focusReleases).isEqualTo(0)
        assertThat(analytics.names).hasSize(3)
    }

    @Test
    fun `the intent is the core's, from the host's when it gave one`() = runTest {
        data class Case(val host: MatrixRtcCallIntent?, val members: MatrixRtcCallIntent?, val isCameraOn: Boolean)
        listOf(
            Case(host = MatrixRtcCallIntent.VIDEO, members = MatrixRtcCallIntent.AUDIO, isCameraOn = true),
            Case(host = MatrixRtcCallIntent.AUDIO, members = MatrixRtcCallIntent.VIDEO, isCameraOn = false),
            Case(host = null, members = MatrixRtcCallIntent.AUDIO, isCameraOn = false),
            Case(host = null, members = MatrixRtcCallIntent.VIDEO, isCameraOn = true),
            Case(host = null, members = null, isCameraOn = true),
        ).forEach { case ->
            val rtcClient = FakeMatrixRtcClient(membersIntent = case.members)
            val controller = createLobby(rtcClient = rtcClient, intent = case.host)
            controller.setMicrophonePermissionGranted(true)
            controller.setCameraPermissionGranted(true)
            runCurrent()
            assertThat(controller.state.value?.isCameraEnabled).isEqualTo(case.isCameraOn)
            assertThat(rtcClient.lastRoom?.callIntentRequests).containsExactly(case.host)

            controller.join()
            runCurrent()
            // Stated on the join, asked once, and nothing after the join turns the camera off (C6).
            val settled = if (case.isCameraOn) MatrixRtcCallIntent.VIDEO else MatrixRtcCallIntent.AUDIO
            assertThat(rtcClient.lastRoom?.lastJoinIntent).isEqualTo(settled)
            assertThat(rtcClient.lastRoom?.callIntentRequests).hasSize(1)
            assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Connected)
            assertThat(rtcClient.lastCall?.lastMediaSession?.cameraEnabledCalls.orEmpty().any { it }).isEqualTo(case.isCameraOn)
        }
    }

    @Test
    fun `a provider that never answers holds nothing back`() = runTest {
        val controller = createLobby(roomContextProvider = FakeElementCallRoomContextProvider(roomContext = null))
        runCurrent()
        assertThat((controller.state.value?.connection as? ElementCallConnection.Lobby)?.isLoading).isFalse()
    }

    @Test
    fun `nothing reaches the room before Join`() = runTest {
        val preferences = FakeElementCallPreferencesStore()
        val rtcClient = FakeMatrixRtcClient(onRoomOpened = { it.memberCount.value = 1 })
        val controller = createLobby(rtcClient = rtcClient, preferencesStore = preferences)
        controller.setMicrophonePermissionGranted(true)
        controller.setCameraPermissionGranted(true)
        runCurrent()
        controller.setCameraEnabled(false)
        controller.setCameraEnabled(true)
        controller.setMicrophoneMuted(true)
        controller.switchCamera()
        controller.setMembershipFormat(MatrixRtcMembershipFormat.ROOM_STATE)
        runCurrent()

        assertThat(rtcClient.rooms.sumOf { it.joinCallCount }).isEqualTo(0)
        assertThat(platform.startForegroundServiceCount).isEqualTo(0)
        assertThat(lifecycleListener.startedCalls).isEmpty()
        assertThat(analytics.events).isEmpty()
        // Other apps' audio keeps playing (R45).
        assertThat(focusRequests).isEqualTo(0)
        assertThat(audioDevices.startCount).isEqualTo(0)
        assertThat(audioDevices.proximityBlankingAllowed).isFalse()
    }

    @Test
    fun `Join is refused with the microphone refused, and a refusal is no failure`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createLobby(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(false)
        runCurrent()
        assertThat(controller.state.value?.isMicrophonePermissionRefused).isTrue()
        assertThat(controller.state.value?.isCameraEnabled).isFalse()

        controller.join()
        runCurrent()

        assertThat(controller.state.value?.connection).isInstanceOf(ElementCallConnection.Lobby::class.java)
        assertThat(rtcClient.lastRoom?.joinCallCount).isEqualTo(0)

        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        controller.join()
        runCurrent()
        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Connected)
    }

    @Test
    fun `Join switches at once, then tells the host, starts the service and joins the lobby's room`() = runTest {
        val joinGate = CompletableDeferred<Unit>()
        val rtcClient = FakeMatrixRtcClient(joinGate = joinGate)
        val controller = createLobby(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        controller.join()
        runCurrent()

        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Joining)
        assertThat(lifecycleListener.startedCalls).hasSize(1)
        assertThat(platform.startForegroundServiceCount).isEqualTo(1)
        assertThat(rtcClient.roomCount).isEqualTo(1)
        assertThat(rtcClient.lastRoom?.joinCallCount).isEqualTo(1)
        // The audio is taken at the tap (R45), and routed once joined.
        assertThat(focusRequests).isEqualTo(1)
        joinGate.complete(Unit)
        runCurrent()
        assertThat(audioDevices.startCount).isEqualTo(1)
        assertThat(analytics.names.first()).isEqualTo("CallJoinAttempted")
    }

    @Test
    fun `only Start notifies, decided by the button at the tap`() = runTest {
        data class Case(val counts: List<Int>, val label: ElementCallLobbyJoinLabel, val kind: MatrixRtcCallKind)
        listOf(
            Case(listOf(0), ElementCallLobbyJoinLabel.START, MatrixRtcCallKind.Group(MatrixRtcGroupNotify(MatrixRtcCallIntent.VIDEO))),
            Case(listOf(2), ElementCallLobbyJoinLabel.JOIN, MatrixRtcCallKind.Group(notify = null)),
            Case(listOf(2, 0), ElementCallLobbyJoinLabel.JOIN_ANYWAY, MatrixRtcCallKind.Group(notify = null)),
        ).forEach { case ->
            val rtcClient = FakeMatrixRtcClient(onRoomOpened = { it.memberCount.value = case.counts.first() })
            // The host's flag is the opposite of what the button says, and is not read.
            val controller = createLobby(rtcClient = rtcClient, isStartingCall = case.label != ElementCallLobbyJoinLabel.START)
            controller.setMicrophonePermissionGranted(true)
            runCurrent()
            case.counts.drop(1).forEach {
                rtcClient.lastRoom!!.memberCount.value = it
                runCurrent()
            }
            assertThat((controller.state.value?.connection as ElementCallConnection.Lobby).joinLabel).isEqualTo(case.label)

            controller.join()
            runCurrent()

            assertThat(rtcClient.lastRoom?.lastKind).isEqualTo(case.kind)
            assertThat(controller.state.value?.callData?.isStartingCall).isEqualTo(case.label == ElementCallLobbyJoinLabel.START)
        }
    }

    @Test
    fun `the label and the ended notice follow the count`() = runTest {
        fun TestScope.labelsFor(counts: List<Int>): List<ElementCallLobbyJoinLabel?> {
            val rtcClient = FakeMatrixRtcClient(onRoomOpened = { it.memberCount.value = counts.first() })
            val controller = createLobby(rtcClient = rtcClient)
            return counts.mapIndexed { index, count ->
                if (index > 0) {
                    rtcClient.lastRoom!!.memberCount.value = count
                    runCurrent()
                }
                (controller.state.value?.connection as ElementCallConnection.Lobby).joinLabel
            }
        }
        val start = ElementCallLobbyJoinLabel.START
        val join = ElementCallLobbyJoinLabel.JOIN
        val anyway = ElementCallLobbyJoinLabel.JOIN_ANYWAY
        assertThat(labelsFor(listOf(0, 3, 0))).containsExactly(start, join, anyway).inOrder()
        assertThat(labelsFor(listOf(2, 0, 1))).containsExactly(join, anyway, join).inOrder()
        assertThat(labelsFor(listOf(0, 0))).containsExactly(start, start).inOrder()
    }

    @Test
    fun `a format change shuts the room, opens it in the new format and loads again from scratch`() = runTest {
        val preferences = FakeElementCallPreferencesStore(membershipFormat = MatrixRtcMembershipFormat.MSC)
        val rtcClient = FakeMatrixRtcClient(onRoomOpened = { it.memberCount.value = if (it.membershipFormat == MatrixRtcMembershipFormat.MSC) 2 else 0 })
        val controller = createLobby(rtcClient = rtcClient, preferencesStore = preferences)
        rtcClient.lastRoom!!.memberCount.value = 0
        runCurrent()
        assertThat((controller.state.value?.connection as ElementCallConnection.Lobby).joinLabel).isEqualTo(ElementCallLobbyJoinLabel.JOIN_ANYWAY)

        controller.setMembershipFormat(MatrixRtcMembershipFormat.ROOM_STATE)
        runCurrent()

        assertThat(rtcClient.rooms.map { it.membershipFormat }).containsExactly(MatrixRtcMembershipFormat.MSC, MatrixRtcMembershipFormat.ROOM_STATE).inOrder()
        assertThat(rtcClient.rooms.first().shutdownCount).isEqualTo(1)
        assertThat(rtcClient.rooms.last().callIntentRequests).hasSize(1)
        // A call that exists only in the old format has not "ended" (C23).
        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Lobby(memberCount = 0, hasSeenMembers = false))
        assertThat(controller.state.value?.joiningFormat).isEqualTo(MatrixRtcMembershipFormat.ROOM_STATE)

        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        controller.join()
        runCurrent()
        assertThat(rtcClient.roomCount).isEqualTo(2)
        assertThat(rtcClient.rooms.last().joinCallCount).isEqualTo(1)
    }

    @Test
    fun `what the user chose in the lobby is what the call joins with`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createLobby(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(true)
        controller.setCameraPermissionGranted(true)
        runCurrent()
        val camera = rtcClient.cameras.single()
        controller.switchCamera()
        controller.setMicrophoneMuted(true)
        controller.setCameraEnabled(false)
        runCurrent()
        assertThat(camera.isFrontFacing.value).isFalse()

        controller.join()
        runCurrent()

        val session = rtcClient.lastCall!!.lastMediaSession!!
        assertThat(session.publishMicrophoneCalls).containsExactly(true)
        assertThat(session.cameraEnabledCalls).isEmpty()
        assertThat(controller.state.value?.isFrontCamera).isFalse()
        // The output follows the settled intent, never the camera turned off (R54).
        assertThat(audioDevices.lastPreferLoudspeaker).isTrue()
        assertThat(audioDevices.selectedDevice.value).isEqualTo(aSpeaker())
    }

    @Test
    fun `a headset is the output a lobby joins on, even on a video call`() = runTest {
        val devices = FakeCallAudioDeviceController(initialDevices = listOf(aBluetoothHeadset(), anEarpiece(), aSpeaker()))
        val controller = createLobby(audioDeviceController = devices)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        controller.join()
        runCurrent()

        assertThat(devices.selectedDevice.value).isEqualTo(aBluetoothHeadset())
    }

    @Test
    fun `the camera opens once from the lobby into the call`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createLobby(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(true)
        controller.setCameraPermissionGranted(true)
        runCurrent()
        val camera = rtcClient.cameras.single()
        assertThat(camera.isCapturing.value).isTrue()

        controller.join()
        runCurrent()

        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Connected)
        assertThat(camera.startCount).isEqualTo(1)
        assertThat(camera.stopCount).isEqualTo(0)
        assertThat(camera.attachCount).isEqualTo(1)
        assertThat(rtcClient.lastCall?.lastCamera).isSameInstanceAs(camera)

        controller.hangUp()
        runCurrent()
        assertThat(camera.isCapturing.value).isFalse()
    }

    @Test
    fun `our camera never reads off between the tap and the call`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createLobby(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(true)
        controller.setCameraPermissionGranted(true)
        runCurrent()
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch { controller.state.collect { it?.let { snapshot -> seen += snapshot.isCameraEnabled } } }
        runCurrent()

        controller.join()
        runCurrent()

        // A moment off would drop our tile's renderer at the join: the black frame R49 rules out.
        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Connected)
        assertThat(seen).doesNotContain(false)
    }

    @Test
    fun `the lobby's camera follows the screen, until Join`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createLobby(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(true)
        controller.setCameraPermissionGranted(true)
        runCurrent()
        val camera = rtcClient.cameras.single()

        controller.setCallScreenVisible(false)
        runCurrent()
        assertThat(camera.isCapturing.value).isFalse()

        controller.setCallScreenVisible(true)
        runCurrent()
        assertThat(camera.isCapturing.value).isTrue()
        assertThat(controller.state.value?.isCameraEnabled).isTrue()

        controller.join()
        runCurrent()
        controller.setCallScreenVisible(false)
        runCurrent()
        assertThat(camera.isCapturing.value).isTrue()
        assertThat(focusReleases).isEqualTo(0)
    }

    @Test
    fun `another call holding the audio stops the camera until it ends`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createLobby(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(true)
        controller.setCameraPermissionGranted(true)
        runCurrent()
        val camera = rtcClient.cameras.single()

        platform.isAnotherCallHoldingAudio.value = true
        runCurrent()
        assertThat(camera.isCapturing.value).isFalse()
        assertThat(controller.state.value?.isCameraEnabled).isTrue()

        platform.isAnotherCallHoldingAudio.value = false
        runCurrent()
        assertThat(camera.isCapturing.value).isTrue()
    }

    @Test
    fun `a lobby opened during a phone call waits for it to end before its camera`() = runTest {
        platform.isAnotherCallHoldingAudio.value = true
        val rtcClient = FakeMatrixRtcClient()
        val controller = createLobby(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(true)
        controller.setCameraPermissionGranted(true)
        runCurrent()
        val camera = rtcClient.cameras.single()
        assertThat(camera.isCapturing.value).isFalse()

        platform.isAnotherCallHoldingAudio.value = false
        runCurrent()
        assertThat(camera.isCapturing.value).isTrue()
    }

    @Test
    fun `a start over a lobby closes it, its room shut before the new call opens one`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createLobby(rtcClient = rtcClient)
        val lobbyRoom = rtcClient.lastRoom!!

        controller.startCall(ElementCallData(roomId = A_ROOM_ID_2, intent = MatrixRtcCallIntent.VIDEO, isStartingCall = true, isDm = true))
        runCurrent()

        assertThat(lobbyRoom.shutdownCount).isEqualTo(1)
        assertThat(controller.state.value?.callData?.roomId).isEqualTo(A_ROOM_ID_2)
        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.RequestingPermission)
        assertThat(rtcClient.cameras.first().isCapturing.value).isFalse()
        assertThat(lifecycleListener.endedCalls).isEmpty()
    }

    @Test
    fun `closing the lobby leaves nothing behind and reports nothing`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createLobby(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(true)
        controller.setCameraPermissionGranted(true)
        runCurrent()
        val camera = rtcClient.cameras.single()

        controller.hangUp()
        runCurrent()

        assertThat(controller.state.value).isNull()
        assertThat(rtcClient.lastRoom?.shutdownCount).isEqualTo(1)
        assertThat(rtcClient.lastRoom?.joinCallCount).isEqualTo(0)
        assertThat(camera.isCapturing.value).isFalse()
        assertThat(lifecycleListener.endedCalls).isEmpty()
        assertThat(analytics.events).isEmpty()
        assertThat(focusReleases).isEqualTo(0)
        assertThat(audioDevices.stopCount).isEqualTo(0)
        assertThat(platform.stopForegroundServiceCount).isEqualTo(0)
    }

    @Test
    fun `a Join that fails ends on the failure screen, never back in the lobby`() = runTest {
        val rtcClient = FakeMatrixRtcClient(joinResult = { Result.failure(MatrixRtcJoinException.TimedOut()) })
        val controller = createLobby(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(true)
        controller.setCameraPermissionGranted(true)
        runCurrent()

        controller.join()
        runCurrent()

        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Failed(ElementCallFailure.JoinTimedOut))
        assertThat(rtcClient.cameras.single().isCapturing.value).isFalse()
    }

    @Test
    fun `the lobby never minimizes and never enters picture-in-picture`() = runTest {
        val controller = createLobby()
        runCurrent()
        controller.setMaximized(false)
        runCurrent()

        assertThat(controller.state.value?.isMaximized).isTrue()
        assertThat(controller.shouldEnterPictureInPicture.value).isFalse()
    }

    @Test
    fun `a camera granted in the lobby starts no service`() = runTest {
        val controller = createLobby()
        controller.setCameraPermissionGranted(true)
        runCurrent()

        assertThat(platform.startForegroundServiceCount).isEqualTo(0)
        assertThat(controller.state.value?.isCameraPermissionGranted).isTrue()
    }

    @Test
    fun `a refused camera is off and said so, and Join stays enabled`() = runTest {
        val controller = createLobby()
        controller.setMicrophonePermissionGranted(true)
        controller.setCameraPermissionGranted(false)
        runCurrent()

        assertThat(controller.state.value?.isCameraEnabled).isFalse()
        assertThat(controller.state.value?.isCameraPermissionRefused).isTrue()
        controller.join()
        runCurrent()
        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Connected)
    }

    private fun TestScope.createLobby(
        rtcClient: FakeMatrixRtcClient = FakeMatrixRtcClient(),
        analytics: FakeElementCallAnalytics = this@CallLobbyTest.analytics,
        audioDeviceController: FakeCallAudioDeviceController = audioDevices,
        preferencesStore: FakeElementCallPreferencesStore = FakeElementCallPreferencesStore(),
        roomContextProvider: FakeElementCallRoomContextProvider = FakeElementCallRoomContextProvider(),
        intent: MatrixRtcCallIntent? = MatrixRtcCallIntent.VIDEO,
        isStartingCall: Boolean = true,
        isDm: Boolean = false,
        skipsLobby: Boolean = false,
    ): DefaultElementCallController {
        val controller = DefaultElementCallController(
            scope = backgroundScope,
            platform = platform,
            rtcClient = rtcClient,
            audioDeviceController = audioDeviceController,
            audioFocus = audioFocus,
            lifecycleListener = lifecycleListener,
            roomContextProvider = roomContextProvider,
            options = ElementCallOptions(),
            preferencesStore = preferencesStore,
            analytics = analytics,
            ownUserId = A_USER_ID,
        )
        controller.setCallScreenVisible(true)
        controller.startCall(ElementCallData(roomId = A_ROOM_ID, intent = intent, isStartingCall = isStartingCall, isDm = isDm, skipsLobby = skipsLobby))
        runCurrent()
        return controller
    }
}
