/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl

import android.content.Intent
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallData
import io.element.android.call.api.ElementCallOptions
import io.element.android.call.api.ElementCallRoomContext
import io.element.android.call.api.rtc.MatrixRtcAudioLevel
import io.element.android.call.api.rtc.MatrixRtcCallEvent
import io.element.android.call.api.rtc.MatrixRtcCallIntent
import io.element.android.call.api.rtc.MatrixRtcElementCallCompat
import io.element.android.call.api.rtc.MatrixRtcEndReason
import io.element.android.call.api.rtc.MatrixRtcFrameEncryptionDiagnostic
import io.element.android.call.api.rtc.MatrixRtcFrameEncryptionState
import io.element.android.call.api.rtc.MatrixRtcLocalState
import io.element.android.call.api.rtc.MatrixRtcNotificationType
import io.element.android.call.api.rtc.MatrixRtcReceiveStats
import io.element.android.call.api.rtc.MatrixRtcScreenCaptureToken
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcStreamRef
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.MatrixRtcTileKind
import io.element.android.call.api.rtc.MatrixRtcTransport
import io.element.android.call.test.A_ROOM_ID
import io.element.android.call.test.FakeElementCallLifecycleListener
import io.element.android.call.test.FakeElementCallRoomContextProvider
import io.element.android.call.test.FakeMatrixRtcService
import io.element.android.call.test.aCameraParticipant
import io.element.android.call.test.aRoster
import io.element.android.call.test.aSharingParticipant
import io.element.android.call.test.aTile
import io.element.android.call.test.audio.FakeAudioFocus
import io.element.android.call.test.audio.FakeCallAudioDeviceController
import io.element.android.call.test.audio.aBluetoothHeadset
import io.element.android.call.test.audio.aSpeaker
import io.element.android.call.test.audio.anEarpiece
import io.element.android.call.tests.testutils.WarmUpRule
import io.element.android.call.tests.testutils.consumeItemsUntilPredicate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

private val A_TRANSPORT = MatrixRtcTransport.LiveKit("https://sfu.example.org/jwt")
private const val A_LOCAL_MEMBER_ID = "6ffd927ac275815176463d6fee57ed62"
private const val A_REMOTE_MEMBER_ID = "2921a8e353fbc938be76f5b2f4946178"
private const val ANOTHER_REMOTE_MEMBER_ID = "3d7f0c7c6f2a4d0b9e8a1c5b7d6e4f21"
private val A_RECEIVE_STATS = MatrixRtcReceiveStats(
    packetsReceived = 1_200,
    packetsLost = 0,
    bytesReceived = 144_000,
    jitter = 0.004,
    framesDecoded = 0,
    framesDropped = 0,
    totalSamplesReceived = 48_000,
    concealedSamples = 24_000,
    silentConcealedSamples = 24_000,
    concealmentEvents = 1,
)

/**
 * The call itself, over fakes of everything it talks to: the RTC service, the audio route, the
 * platform seam and the host's ports. What the screen makes of the snapshot is tested in
 * `ElementCallScreenStateTest`, in the ui module, against a fake of this.
 */
@Suppress("LargeClass")
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultElementCallControllerTest {
    @get:Rule val warmUpRule = WarmUpRule()

    @Test
    fun `a requested call waits for the microphone permission and tells the host it started`() = runTest {
        val lifecycleListener = FakeElementCallLifecycleListener()
        val controller = createController(lifecycleListener = lifecycleListener)

        val initialState = controller.state.value!!
        assertThat(initialState.connection).isEqualTo(ElementCallConnection.RequestingPermission)
        assertThat(initialState.isMicrophonePermissionGranted).isFalse()
        assertThat(initialState.memberCount).isEqualTo(0)
        assertThat(lifecycleListener.startedCalls.map { it.roomId }).containsExactly(A_ROOM_ID)
    }

    @Test
    fun `a second start while a call is running is ignored`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService)

        controller.startCall(ElementCallData(roomId = A_ROOM_ID, isAudioCall = false))
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(controller.state.value?.callData?.isAudioCall).isTrue()
        assertThat(rtcService.joinCallCount).isEqualTo(1)
    }

    @Test
    fun `denying the microphone fails the call rather than joining silently`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService)

        controller.state.filterNotNull().test {
            awaitItem()
            controller.setMicrophonePermissionGranted(false)

            val states = consumeItemsUntilPredicate { it.connection is ElementCallConnection.Failed }
            assertThat(states.last().connection).isInstanceOf(ElementCallConnection.Failed::class.java)
            assertThat(rtcService.joinCallCount).isEqualTo(0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `granting the microphone joins and connects media`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService)

        controller.state.filterNotNull().test {
            awaitItem()
            controller.setMicrophonePermissionGranted(true)

            val states = consumeItemsUntilPredicate { it.connection == ElementCallConnection.Connected }
            assertThat(states.last().isMicrophonePermissionGranted).isTrue()
            assertThat(rtcService.joinCallCount).isEqualTo(1)
            assertThat(rtcService.lastSession?.connectMediaCount).isEqualTo(1)
            assertThat(rtcService.lastSession?.lastCall?.publishMicrophoneCount).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Spec rule R8: the call works with no UI attached at all. Nothing collects the state here - the
     * call is driven exactly as the host's Activity would drive it and then left alone - and it still
     * joins, holds the foreground service and the audio route, and publishes.
     */
    @Test
    fun `an audio call joins, publishes the microphone and survives with no screen collecting its state`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val audioDevices = FakeCallAudioDeviceController()
        val platform = FakeElementCallPlatform()
        var startedServiceBeforeCapture: Boolean? = null
        val audioFocus = FakeAudioFocus(
            // Audio focus is taken right before capture, so this is the moment to look.
            requestAudioFocusResult = { startedServiceBeforeCapture = platform.startForegroundServiceCount == 1 },
            releaseAudioFocusResult = {},
        )
        val controller = createController(
            rtcService = rtcService,
            audioDeviceController = audioDevices,
            platform = platform,
            audioFocus = audioFocus,
        )

        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Connected)
        assertThat(startedServiceBeforeCapture).isTrue()
        assertThat(rtcService.lastSession?.lastCall?.publishMicrophoneCount).isEqualTo(1)
        assertThat(audioDevices.startCount).isEqualTo(1)
        assertThat(audioDevices.stopCount).isEqualTo(0)
        assertThat(platform.stopForegroundServiceCount).isEqualTo(0)
        assertThat(rtcService.lastSession?.leaveCount).isEqualTo(0)
    }

    /**
     * The roster used to be left unsubscribed in the state-event mode, because no membership reached the
     * core to project. Room state is readable now and `RoomStateFeeder` feeds it, so the count is as real
     * here as anywhere - and a mode-specific branch that skipped the subscription would report a call with
     * nobody in it while media and key distribution both worked.
     */
    @Test
    fun `a call reports the core's member count`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService)

        controller.state.filterNotNull().test {
            awaitItem()
            controller.setMicrophonePermissionGranted(true)
            consumeItemsUntilPredicate { it.connection == ElementCallConnection.Connected }

            // memberCount, not members.size. Both say who is in the slot, but the core answers them
            // differently: memberCount is a question we ask and it counts on the spot, while members
            // arrives on a subscription the core has to wake - and it does not wake it for the
            // Element Call compat path, so members can sit empty for an entire working call.
            // Pushed rather than read at zero, which is the initial value and would pass unsubscribed.
            rtcService.lastSession?.memberCount?.value = 1

            val states = consumeItemsUntilPredicate { it.memberCount == 1 }
            assertThat(states.last().memberCount).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Temporary, with the pin it tests: while the library builds against the released Rust SDK only the
     * state-event generation of Element Call can be bridged, so the option is read but not obeyed.
     * Restore per-mode tests when the pin goes (see `docs/FEEDBACK.md`, "Widget-driver stopgap").
     */
    @Test
    fun `a call joins in the state-event Element Call compatibility whatever the option says`() = runTest {
        MatrixRtcElementCallCompat.entries.forEach { preferred ->
            val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
            val controller = createController(rtcService = rtcService, elementCallCompat = preferred)

            controller.state.filterNotNull().test {
                awaitItem()
                controller.setMicrophonePermissionGranted(true)

                consumeItemsUntilPredicate { it.connection == ElementCallConnection.Connected }
                // Passed to the join rather than merely stored: the mode also fixes the member id, the SFU
                // identity and the token endpoint, so a session joined with the wrong one connects and
                // stays silent rather than failing.
                assertThat(rtcService.lastElementCallCompat).isEqualTo(MatrixRtcElementCallCompat.STATE_EVENTS)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun `a homeserver with no livekit transport fails the call before joining`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = emptyList())
        val controller = createController(rtcService = rtcService)

        controller.state.filterNotNull().test {
            awaitItem()
            controller.setMicrophonePermissionGranted(true)

            val states = consumeItemsUntilPredicate { it.connection is ElementCallConnection.Failed }
            val failure = states.last().connection as ElementCallConnection.Failed
            assertThat(failure.message).contains("LiveKit")
            assertThat(rtcService.joinCallCount).isEqualTo(0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hanging up disconnects the media, leaves the session and clears the call`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val lifecycleListener = FakeElementCallLifecycleListener()
        val controller = createController(rtcService = rtcService, lifecycleListener = lifecycleListener)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        controller.hangUp()
        runCurrent()

        assertThat(rtcService.lastSession?.lastCall?.disconnectCount).isEqualTo(1)
        assertThat(rtcService.lastSession?.leaveCount).isEqualTo(1)
        // Null rather than Ended: the screen closes on the call going away, and the bar with it.
        assertThat(controller.state.value).isNull()
        assertThat(lifecycleListener.endedCalls.map { it?.roomId }).containsExactly(A_ROOM_ID)
    }

    @Test
    fun `a call ended by the far end is torn down without leaving`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        rtcService.lastSession?.lastCall!!.emit(MatrixRtcCallEvent.Ended(MatrixRtcEndReason.ConnectionClosed("SFU went away")))
        runCurrent()

        assertThat(controller.state.value).isNull()
        assertThat(rtcService.lastSession?.lastCall?.disconnectCount).isEqualTo(1)
        assertThat(rtcService.lastSession?.leaveCount).isEqualTo(0)
    }

    @Test
    fun `muting is forwarded to the call and read back from it`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService)

        controller.state.filterNotNull().test {
            awaitItem()
            controller.setMicrophonePermissionGranted(true)
            consumeItemsUntilPredicate { it.connection == ElementCallConnection.Connected }

            controller.setMicrophoneMuted(true)

            val muted = consumeItemsUntilPredicate { it.isMicrophoneMuted }.last()
            assertThat(muted.isMicrophoneMuted).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `audio levels reach the state`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService)

        controller.state.filterNotNull().test {
            awaitItem()
            controller.setMicrophonePermissionGranted(true)
            consumeItemsUntilPredicate { it.connection == ElementCallConnection.Connected }

            val call = rtcService.lastSession?.lastCall!!
            call.audioLevels.value = mapOf("aRemoteMemberId" to MatrixRtcAudioLevel(level = 0.5f, frameCount = 42))

            val state = consumeItemsUntilPredicate { it.audioLevels.isNotEmpty() }.last()
            assertThat(state.audioLevels["aRemoteMemberId"]).isEqualTo(MatrixRtcAudioLevel(level = 0.5f, frameCount = 42))
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The meter publishes ten times a second per member, and in a large call that was over a
     * hundred states a second from levels alone. Sampled, a burst of readings inside one window
     * reaches the state once, as the latest value.
     */
    @Test
    fun `audio levels are sampled rather than forwarded one by one`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService)

        controller.state.filterNotNull().test {
            awaitItem()
            controller.setMicrophonePermissionGranted(true)
            consumeItemsUntilPredicate { it.connection == ElementCallConnection.Connected }
            val call = rtcService.lastSession?.lastCall!!

            // Two readings with no time passing between them: a window's worth of a meter.
            call.audioLevels.value = mapOf("aRemoteMemberId" to MatrixRtcAudioLevel(level = 0.1f, frameCount = 1))
            call.audioLevels.value = mapOf("aRemoteMemberId" to MatrixRtcAudioLevel(level = 0.9f, frameCount = 2))
            advanceTimeBy(DefaultElementCallController.AUDIO_LEVEL_SAMPLE_MS + 1)
            runCurrent()

            val states = consumeItemsUntilPredicate { it.audioLevels.isNotEmpty() }
            val levels = states.mapNotNull { it.audioLevels["aRemoteMemberId"] }.distinct()
            // The first reading never made it to the state; only the latest did.
            assertThat(levels).containsExactly(MatrixRtcAudioLevel(level = 0.9f, frameCount = 2))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `receive stats and a frame encryption failure reach the state`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService)

        controller.state.filterNotNull().test {
            awaitItem()
            controller.setMicrophonePermissionGranted(true)
            consumeItemsUntilPredicate { it.connection == ElementCallConnection.Connected }

            val call = rtcService.lastSession?.lastCall!!
            call.receiveStats.value = mapOf(MatrixRtcStreamRef("aRemoteMemberId", MatrixRtcStreamKind.MICROPHONE) to A_RECEIVE_STATS)
            call.emit(
                MatrixRtcCallEvent.FrameEncryption(
                    memberId = "aRemoteMemberId",
                    state = MatrixRtcFrameEncryptionState.MISSING_KEY,
                    diagnostic = MatrixRtcFrameEncryptionDiagnostic.NoKeyInstalled,
                )
            )

            val state = consumeItemsUntilPredicate { it.frameEncryption.isNotEmpty() }.last()
            assertThat(state.receiveStats[MatrixRtcStreamRef("aRemoteMemberId", MatrixRtcStreamKind.MICROPHONE)]).isEqualTo(A_RECEIVE_STATS)
            assertThat(state.frameEncryption["aRemoteMemberId"]).isEqualTo(MatrixRtcFrameEncryptionState.MISSING_KEY)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a degraded media connection is reported and clears again`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val call = rtcService.lastSession?.lastCall!!

        call.emit(MatrixRtcCallEvent.MediaConnectionDegraded(degraded = true))
        runCurrent()
        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Degraded)

        call.emit(MatrixRtcCallEvent.MediaConnectionDegraded(degraded = false))
        runCurrent()
        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Connected)
    }

    @Test
    fun `the test tone is forwarded to the call`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService)

        controller.state.filterNotNull().test {
            awaitItem()
            controller.setMicrophonePermissionGranted(true)
            consumeItemsUntilPredicate { it.connection == ElementCallConnection.Connected }

            controller.setAudioTestToneEnabled(true)

            val toneOn = consumeItemsUntilPredicate { it.isAudioTestToneEnabled }.last()
            assertThat(toneOn.isAudioTestToneEnabled).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `connecting takes the audio route and offers every device`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val audioDevices = FakeCallAudioDeviceController(initialDevices = listOf(anEarpiece(), aSpeaker()))
        val controller = createController(rtcService = rtcService, audioDeviceController = audioDevices)

        controller.state.filterNotNull().test {
            awaitItem()
            controller.setMicrophonePermissionGranted(true)
            val connected = consumeItemsUntilPredicate { it.connection == ElementCallConnection.Connected }.last()

            // The route is taken, and it is chosen rather than left to whatever the platform had:
            // a voice call that starts on the loudspeaker is startling in a way the reverse is not.
            assertThat(audioDevices.startCount).isEqualTo(1)
            assertThat(audioDevices.lastPreferLoudspeaker).isFalse()
            assertThat(connected.selectedAudioDevice).isEqualTo(anEarpiece())
            assertThat(connected.audioDevices).containsExactly(anEarpiece(), aSpeaker()).inOrder()

            controller.selectAudioDevice(aSpeaker())

            val onSpeaker = consumeItemsUntilPredicate { it.selectedAudioDevice == aSpeaker() }.last()
            assertThat(onSpeaker.selectedAudioDevice).isEqualTo(aSpeaker())
            assertThat(audioDevices.selections.last()).isEqualTo(aSpeaker())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * A video call is looked at rather than held to an ear, so the earpiece is the wrong default for
     * it: the first thing everyone did on a video call was hunt for the speaker button.
     */
    @Test
    fun `a video call starts on the loudspeaker`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val audioDevices = FakeCallAudioDeviceController(initialDevices = listOf(anEarpiece(), aSpeaker()))
        val controller = createController(rtcService = rtcService, audioDeviceController = audioDevices, isAudioCall = false)

        controller.state.filterNotNull().test {
            awaitItem()
            controller.setMicrophonePermissionGranted(true)
            val connected = consumeItemsUntilPredicate { it.connection == ElementCallConnection.Connected }.last()

            assertThat(audioDevices.lastPreferLoudspeaker).isTrue()
            assertThat(connected.selectedAudioDevice).isEqualTo(aSpeaker())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a video call still prefers a headset over the loudspeaker`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val audioDevices = FakeCallAudioDeviceController(initialDevices = listOf(aBluetoothHeadset(), anEarpiece(), aSpeaker()))
        val controller = createController(rtcService = rtcService, audioDeviceController = audioDevices, isAudioCall = false)

        controller.state.filterNotNull().test {
            awaitItem()
            controller.setMicrophonePermissionGranted(true)
            val connected = consumeItemsUntilPredicate { it.connection == ElementCallConnection.Connected }.last()

            // Pairing a headset is itself the instruction, whatever kind of call it is.
            assertThat(connected.selectedAudioDevice).isEqualTo(aBluetoothHeadset())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * A headset paired mid-call has to show up without anything being asked, because that is the
     * whole case the picker exists for - and the one the WebView path handles worst, since there the
     * list has to survive a round trip through a web app that owns the selection.
     */
    @Test
    fun `a device connected mid-call appears in the list`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val audioDevices = FakeCallAudioDeviceController(initialDevices = listOf(anEarpiece(), aSpeaker()))
        val controller = createController(rtcService = rtcService, audioDeviceController = audioDevices)

        controller.state.filterNotNull().test {
            awaitItem()
            controller.setMicrophonePermissionGranted(true)
            consumeItemsUntilPredicate { it.connection == ElementCallConnection.Connected }

            audioDevices.devices.value = listOf(aBluetoothHeadset(), anEarpiece(), aSpeaker())

            val state = consumeItemsUntilPredicate { it.audioDevices.size == 3 }.last()
            assertThat(state.audioDevices).contains(aBluetoothHeadset())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Hanging up releases the route, and *only* hanging up does.
     *
     * The call outlives every screen that draws it, so nothing about a screen going away may hand
     * the route back - that would cut the audio of a call the user had merely minimized.
     * Communication mode is device-wide state, so the other half matters just as much: releasing has
     * to actually happen on hangup, or every other app on the device keeps routing to the earpiece.
     */
    @Test
    fun `hanging up hands the audio route and the foreground service back`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val audioDevices = FakeCallAudioDeviceController()
        val platform = FakeElementCallPlatform()
        var releaseCount = 0
        val controller = createController(
            rtcService = rtcService,
            audioDeviceController = audioDevices,
            platform = platform,
            audioFocus = FakeAudioFocus(requestAudioFocusResult = {}, releaseAudioFocusResult = { releaseCount++ }),
        )
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        assertThat(audioDevices.stopCount).isEqualTo(0)

        controller.hangUp()
        runCurrent()

        assertThat(audioDevices.stopCount).isEqualTo(1)
        assertThat(releaseCount).isEqualTo(1)
        assertThat(platform.stopForegroundServiceCount).isEqualTo(1)
    }

    /**
     * Minimizing is a change of view, not a change to the call.
     *
     * The distinction is the entire point of hosting the call outside a screen: everything a user
     * would notice going wrong here - audio stopping, the session being left, the route handed back -
     * is what happens if minimizing is confused with leaving.
     */
    @Test
    fun `minimizing keeps the call running and can be undone`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val audioDevices = FakeCallAudioDeviceController()
        val platform = FakeElementCallPlatform()
        val controller = createController(rtcService = rtcService, audioDeviceController = audioDevices, platform = platform)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        assertThat(controller.state.value?.isMaximized).isTrue()

        controller.setMaximized(false)
        runCurrent()

        assertThat(controller.state.value?.isMaximized).isFalse()
        // Still connected, still holding the route, still a member of the session.
        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Connected)
        assertThat(audioDevices.stopCount).isEqualTo(0)
        assertThat(platform.stopForegroundServiceCount).isEqualTo(0)
        assertThat(rtcService.lastSession?.leaveCount).isEqualTo(0)

        // What tapping the minimized bar does.
        controller.setMaximized(true)
        assertThat(controller.state.value?.isMaximized).isTrue()
    }

    /**
     * A video call starts with the camera on.
     *
     * `ElementCallData.isAudioCall` is carried all the way from the caller's `m.call.intent`, through the
     * push, the ringing notification and the answer - and was then dropped on the floor here, so
     * every call started audio-only however it was placed or answered.
     */
    @Test
    fun `a video call turns the camera on once connected`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService, isAudioCall = false)

        // The host asks for the camera up front on a video call, so it is granted before media
        // connects. This is the ordering where the controller has to act on it itself.
        controller.setCameraPermissionGranted(true)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Connected)
        assertThat(rtcService.lastSession?.lastCall?.cameraEnabledCalls).contains(true)
    }

    /**
     * The button says what the call will join with, from the first frame.
     *
     * It used to say what was capturing, which before the media connects is nothing at all: a video
     * call showed the camera off for the whole join and then flipped it on under the user's finger,
     * announcing the wrong call and changing its mind.
     */
    @Test
    fun `a video call shows the camera on before it connects`() = runTest {
        val videoCall = createController(isAudioCall = false)
        assertThat(videoCall.state.value?.connection).isEqualTo(ElementCallConnection.RequestingPermission)
        assertThat(videoCall.state.value?.isCameraEnabled).isTrue()

        val audioCall = createController(isAudioCall = true)
        assertThat(audioCall.state.value?.isCameraEnabled).isFalse()
    }

    /**
     * The control bar is on screen from the first frame, so both buttons can be pressed while the
     * call is still connecting - there is no separate lobby screen for them to live on. What they
     * record is what the call joins with.
     *
     * For the microphone that is the state the publication itself is made in, not a mute applied
     * after it: a track published unmuted and muted a moment later shows every peer an unmuted
     * member for exactly as long as the roster takes to draw for the first time.
     */
    @Test
    fun `muting while the call connects publishes the microphone already muted`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService)

        // Nothing to mute yet: the permission has not been answered, so there is no session, let
        // alone a call. The tap has nowhere to go but the snapshot.
        controller.setMicrophoneMuted(true)
        runCurrent()
        assertThat(controller.state.value?.isMicrophoneMuted).isTrue()

        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Connected)
        assertThat(rtcService.lastSession?.lastCall?.publishMicrophoneCalls).containsExactly(true)
        // And it is still muted once connected: the observers start after the publication, so what
        // they report back is the state it was published in rather than a default that undoes it.
        assertThat(controller.state.value?.isMicrophoneMuted).isTrue()
    }

    @Test
    fun `a microphone nobody touched is published unmuted`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService)

        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(rtcService.lastSession?.lastCall?.publishMicrophoneCalls).containsExactly(false)
        assertThat(controller.state.value?.isMicrophoneMuted).isFalse()
    }

    @Test
    fun `a mute taken back while the call connects publishes the microphone unmuted`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService)

        controller.setMicrophoneMuted(true)
        controller.setMicrophoneMuted(false)
        runCurrent()

        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(rtcService.lastSession?.lastCall?.publishMicrophoneCalls).containsExactly(false)
        assertThat(controller.state.value?.isMicrophoneMuted).isFalse()
    }

    @Test
    fun `turning the camera on while the call connects starts it once connected`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService, isAudioCall = true)

        // The permission answered first, which is what makes the button live: without it the tap
        // asks for the permission instead, and that answer is not an ask for the camera.
        controller.setCameraPermissionGranted(true)
        controller.setCameraEnabled(true)
        runCurrent()
        assertThat(controller.state.value?.isCameraEnabled).isTrue()

        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(rtcService.lastSession?.lastCall?.cameraEnabledCalls).containsExactly(true)
        assertThat(controller.state.value?.isCameraEnabled).isTrue()
    }

    /**
     * The mirror image, and the reason the ask is kept beside the snapshot rather than read off it:
     * a video call starts the camera by default, so "off" has to be distinguishable from "untouched".
     */
    @Test
    fun `turning the camera off while a video call connects leaves it off`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService, isAudioCall = false)

        controller.setCameraPermissionGranted(true)
        controller.setCameraEnabled(false)
        runCurrent()

        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Connected)
        assertThat(rtcService.lastSession?.lastCall?.cameraEnabledCalls).isEmpty()
        assertThat(controller.state.value?.isCameraEnabled).isFalse()
    }

    @Test
    fun `an audio call leaves the camera off`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService, isAudioCall = true)

        controller.setCameraPermissionGranted(true)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        // Granting the camera is not the same as wanting it on: an audio call that answered the
        // permission dialog must still start with the camera off.
        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Connected)
        assertThat(rtcService.lastSession?.lastCall?.isCameraEnabled?.value).isFalse()
    }

    /**
     * Answering has to tell the host it was answered.
     *
     * Nothing about the call itself depends on this, which is exactly why it is easy to leave out
     * and hard to spot in code review - the failure is entirely in the surrounding experience: the
     * ringtone keeps playing over the connected call, the full-screen incoming UI stays up, and 90
     * seconds later a missed-call notification arrives for a call that was answered.
     */
    @Test
    fun `connecting reports the join, and ending reports the end`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val lifecycleListener = FakeElementCallLifecycleListener()
        val controller = createController(rtcService = rtcService, lifecycleListener = lifecycleListener)

        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(lifecycleListener.joinedCalls.map { it.roomId }).containsExactly(A_ROOM_ID)
        assertThat(lifecycleListener.endedCalls).isEmpty()

        controller.hangUp()
        runCurrent()

        assertThat(lifecycleListener.endedCalls.map { it?.roomId }).containsExactly(A_ROOM_ID)
    }

    /**
     * Two tiles on one member must open one stream, not two.
     *
     * `MatrixRtcCall.videoFrames` is cold and documents "collect it once per member; two collectors
     * means two streams". The call UI legitimately draws a member twice - spotlight and strip - so
     * the controller shares the flow. Getting this wrong is not a rendering glitch: two handles on
     * one track, with the second closing under the first, crashed the core inside
     * `VideoSinkWrapper::on_frame` a few seconds after video started.
     */
    @Test
    fun `two collectors on one member open a single video stream`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val call = rtcService.lastSession?.lastCall!!

        val first = backgroundScope.launch { controller.videoFrames(A_REMOTE_MEMBER_ID).collect { } }
        val second = backgroundScope.launch { controller.videoFrames(A_REMOTE_MEMBER_ID).collect { } }
        runCurrent()

        assertThat(call.openVideoStreamsFor(A_REMOTE_MEMBER_ID)).isEqualTo(1)

        // And the stream closes once the last tile stops drawing, which is what keeps a
        // minimized call from decoding video nobody is looking at - after the linger, which is
        // what stops a tile *moving* from being read as a tile leaving.
        first.cancel()
        second.cancel()
        advanceTimeBy(DefaultElementCallController.VIDEO_STREAM_LINGER_MS + 1)
        runCurrent()
        assertThat(call.openVideoStreamsFor(A_REMOTE_MEMBER_ID)).isEqualTo(0)
    }

    /**
     * A member moving between the spotlight and the strip must not close their video stream.
     *
     * Compose disposes the old tile before composing the new one, so the subscriber count touches
     * zero in between. Reading that as "nobody is watching" closed the stream and immediately
     * reopened it - and the close raced a frame already in flight, releasing the decoder underneath
     * libwebrtc's output thread and aborting the process inside `VideoSinkWrapper::on_frame`. It
     * reproduced whenever a third participant joined and the spotlight moved.
     */
    @Test
    fun `a member moving between tiles keeps one stream open throughout`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val call = rtcService.lastSession?.lastCall!!

        val spotlightTile = backgroundScope.launch { controller.videoFrames(A_REMOTE_MEMBER_ID).collect { } }
        runCurrent()
        assertThat(call.openVideoStreamsFor(A_REMOTE_MEMBER_ID)).isEqualTo(1)

        // The spotlight moves to someone else, so this member's tile is disposed and rebuilt in
        // the strip. Disposal first, exactly as Compose does it.
        spotlightTile.cancel()
        runCurrent()
        val stripTile = backgroundScope.launch { controller.videoFrames(A_REMOTE_MEMBER_ID).collect { } }
        runCurrent()

        // Still the same stream. The open *count* is what proves it: a stream that closed and
        // reopened would leave the currently-open number back at one and look identical.
        assertThat(call.openVideoStreamsFor(A_REMOTE_MEMBER_ID)).isEqualTo(1)
        assertThat(call.videoStreamOpenCountFor(A_REMOTE_MEMBER_ID)).isEqualTo(1)

        stripTile.cancel()
    }

    /** With no call running there is nothing to stream, and asking must not blow up a tile that is on its way out. */
    @Test
    fun `video frames with no call running are an empty flow`() = runTest {
        val controller = createController()

        var frameCount = 0
        controller.videoFrames(A_REMOTE_MEMBER_ID).collect { frameCount++ }

        assertThat(frameCount).isEqualTo(0)
    }

    /**
     * The screen declares what it composes before media is up, so the set has to survive until the
     * call exists - and reach every call the controller starts after it.
     */
    @Test
    fun `the composed tiles reach the call, including one that connects later`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService)
        val composed = setOf(MatrixRtcTileId(A_REMOTE_MEMBER_ID, MatrixRtcTileKind.PERSON))
        controller.setComposedTiles(composed)

        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val call = rtcService.lastSession?.lastCall!!
        assertThat(call.composedTiles).containsExactly(composed)

        val more = composed + MatrixRtcTileId(ANOTHER_REMOTE_MEMBER_ID, MatrixRtcTileKind.SCREEN_SHARE)
        controller.setComposedTiles(more)
        assertThat(call.composedTiles).containsExactly(composed, more).inOrder()
    }

    /**
     * The spotlight is the head of the core's order and nothing else.
     *
     * Speaker events used to move it here, behind a dwell of our own; the core now damps the speaking
     * input it ranks on and no speaker event reaches this layer at all.
     */
    @Test
    fun `the spotlight is the head of the order`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val call = rtcService.lastSession?.lastCall!!

        call.tiles.value = aRoster(aTile(A_REMOTE_MEMBER_ID), aTile(ANOTHER_REMOTE_MEMBER_ID))
        runCurrent()
        assertThat(controller.state.value?.spotlightTileId).isEqualTo(MatrixRtcTileId(A_REMOTE_MEMBER_ID, MatrixRtcTileKind.PERSON))

        call.tiles.value = aRoster(aTile(ANOTHER_REMOTE_MEMBER_ID, isSpeaking = true), aTile(A_REMOTE_MEMBER_ID))
        runCurrent()
        assertThat(controller.state.value?.spotlightTileId?.memberId).isEqualTo(ANOTHER_REMOTE_MEMBER_ID)
    }

    @Test
    fun `a sharer's screen takes the spotlight rather than their camera`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val call = rtcService.lastSession?.lastCall!!

        call.tiles.value = aRoster(aTile(A_REMOTE_MEMBER_ID, MatrixRtcTileKind.SCREEN_SHARE), aTile(A_REMOTE_MEMBER_ID))
        runCurrent()

        assertThat(controller.state.value?.spotlightTileId).isEqualTo(MatrixRtcTileId(A_REMOTE_MEMBER_ID, MatrixRtcTileKind.SCREEN_SHARE))
        assertThat(controller.state.value?.tiles?.map { it.id.kind }).containsExactly(MatrixRtcTileKind.SCREEN_SHARE, MatrixRtcTileKind.PERSON).inOrder()
    }

    /** Never ourselves: the core never ranks our own tile, so alone there is nobody to spotlight. */
    @Test
    fun `the spotlight is never us, and empties when we are alone`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val call = rtcService.lastSession?.lastCall!!

        call.participants.value = listOf(aCameraParticipant(A_LOCAL_MEMBER_ID, isLocal = true, isCameraMuted = false))
        runCurrent()
        assertThat(controller.state.value?.spotlightTileId).isNull()
        assertThat(controller.state.value?.ownTile?.id?.memberId).isEqualTo(A_LOCAL_MEMBER_ID)
    }

    /**
     * The core publishes our tile only once our membership reaches its roster, which is after the
     * transport first lists us. Without the fallback every join would open on an empty stage.
     */
    @Test
    fun `our own tile comes from the transport until the core publishes it`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val call = rtcService.lastSession?.lastCall!!

        call.participants.value = listOf(aCameraParticipant(A_LOCAL_MEMBER_ID, isLocal = true, isCameraMuted = true))
        runCurrent()
        assertThat(controller.state.value?.ownTile?.hasVideo).isFalse()

        call.localState.value = MatrixRtcLocalState(tile = aTile(A_LOCAL_MEMBER_ID, hasVideo = true, isSpeaking = true), isScreenSharing = false)
        runCurrent()
        assertThat(controller.state.value?.ownTile?.hasVideo).isTrue()
        assertThat(controller.state.value?.ownTile?.isSpeaking).isTrue()
    }

    /**
     * The only way to reach this is having just tapped the camera button, so making the user tap it
     * again after saying yes would be a strange reward for granting it.
     */
    @Test
    fun `granting the camera permission turns the camera on`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        controller.setCameraPermissionGranted(true)
        runCurrent()

        assertThat(controller.state.value?.isCameraPermissionGranted).isTrue()
        assertThat(controller.state.value?.isCameraEnabled).isTrue()
        assertThat(rtcService.lastSession?.lastCall?.cameraEnabledCalls).containsExactly(true)
    }

    @Test
    fun `turning the camera off once it is on stops it`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        controller.setCameraPermissionGranted(true)
        runCurrent()

        controller.setCameraEnabled(false)
        runCurrent()

        assertThat(controller.state.value?.isCameraEnabled).isFalse()
        assertThat(rtcService.lastSession?.lastCall?.cameraEnabledCalls).containsExactly(true, false).inOrder()
    }

    /**
     * The camera side is asynchronous - it has to close one device and open another - so the state
     * has to come back from the call rather than being assumed at the tap.
     */
    @Test
    fun `switching the camera follows what the call reports rather than the request`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        // Front facing before the camera has ever been opened, which is the side it starts on.
        assertThat(controller.state.value?.isFrontCamera).isTrue()

        controller.switchCamera()
        runCurrent()

        assertThat(controller.state.value?.isFrontCamera).isFalse()
        assertThat(rtcService.lastSession?.lastCall?.switchCameraCount).isEqualTo(1)
    }

    /**
     * The proximity sensor blanks the screen only when the phone might really be at an ear.
     *
     * Held indiscriminately - which is what it used to be, on the earpiece alone - the lock turns the
     * display off whenever a hand goes near the top of the screen, because that is where the sensor
     * is. Minimized that becomes a trap rather than a nuisance: reaching for the notification shade
     * is reaching for the top of the screen, so the one gesture that could get back to the call is
     * the gesture that blanks it.
     *
     * Worth a test precisely because it cannot fail anywhere but on a device with a phone in hand.
     */
    @Test
    fun `the proximity sensor is only allowed to blank a maximized audio call in the foreground`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val audioDeviceController = FakeCallAudioDeviceController()
        val lifecycleListener = FakeElementCallLifecycleListener()
        val controller = createController(
            rtcService = rtcService,
            audioDeviceController = audioDeviceController,
            lifecycleListener = lifecycleListener,
        )
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val call = rtcService.lastSession?.lastCall!!
        call.participants.value = listOf(aCameraParticipant(A_REMOTE_MEMBER_ID, isLocal = false, isCameraMuted = true))
        runCurrent()

        // Maximized, audio only, foreground: the one case it is for.
        assertThat(audioDeviceController.proximityBlankingAllowed).isTrue()

        // Someone turns a camera on - now there is something to look at.
        call.participants.value = listOf(aCameraParticipant(A_REMOTE_MEMBER_ID, isLocal = false, isCameraMuted = false))
        runCurrent()
        assertThat(audioDeviceController.proximityBlankingAllowed).isFalse()

        call.participants.value = listOf(aCameraParticipant(A_REMOTE_MEMBER_ID, isLocal = false, isCameraMuted = true))
        runCurrent()
        assertThat(audioDeviceController.proximityBlankingAllowed).isTrue()

        // Minimized: the user is reading something else, and needs the top of their screen.
        controller.setMaximized(false)
        runCurrent()
        assertThat(audioDeviceController.proximityBlankingAllowed).isFalse()

        controller.setMaximized(true)
        runCurrent()
        assertThat(audioDeviceController.proximityBlankingAllowed).isTrue()

        // Backgrounded: blanking another app's screen is never ours to do.
        lifecycleListener.isAppInForeground.value = false
        runCurrent()
        assertThat(audioDeviceController.proximityBlankingAllowed).isFalse()
    }

    /**
     * A minimized call docks as a bar or floats as a tile depending on whether it has a picture.
     *
     * Driven by what is actually being published rather than by how the call was placed, so an audio
     * call that turns into a video one changes shape with it, and a video call whose cameras have all
     * gone off falls back to the bar rather than floating an avatar over the app for no reason.
     */
    @Test
    fun `only a call with video floats as a tile when minimized`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val call = rtcService.lastSession?.lastCall!!

        // Audio only: the bar.
        call.participants.value = listOf(aCameraParticipant(A_REMOTE_MEMBER_ID, isLocal = false, isCameraMuted = true))
        runCurrent()
        assertThat(controller.state.value?.hasVideo).isFalse()

        // Someone turns a camera on: the tile.
        call.participants.value = listOf(aCameraParticipant(A_REMOTE_MEMBER_ID, isLocal = false, isCameraMuted = false))
        runCurrent()
        assertThat(controller.state.value?.hasVideo).isTrue()

        // A screen share counts too, even with every camera off.
        call.participants.value = listOf(aSharingParticipant(A_REMOTE_MEMBER_ID))
        runCurrent()
        assertThat(controller.state.value?.hasVideo).isTrue()

        // And back off again.
        call.participants.value = listOf(aCameraParticipant(A_REMOTE_MEMBER_ID, isLocal = false, isCameraMuted = true))
        runCurrent()
        assertThat(controller.state.value?.hasVideo).isFalse()
    }

    /**
     * Only a maximized call follows the user out of the app as a floating window.
     *
     * The negative half matters most: with no call, or with one docked in the bar, backgrounding the
     * app has to behave exactly as it did before picture-in-picture existed. A floating window over
     * the launcher because someone was reading a room with a call minimized would be a regression for
     * every user of the app, not a feature.
     */
    @Test
    fun `only a maximized call asks to enter picture-in-picture`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService)

        // A call was started by createController, and it starts maximized.
        assertThat(controller.shouldEnterPictureInPicture.value).isTrue()

        controller.setMaximized(false)
        runCurrent()
        assertThat(controller.shouldEnterPictureInPicture.value).isFalse()

        controller.setMaximized(true)
        runCurrent()
        assertThat(controller.shouldEnterPictureInPicture.value).isTrue()

        controller.hangUp()
        runCurrent()
        assertThat(controller.shouldEnterPictureInPicture.value).isFalse()
    }

    /**
     * Leaving picture-in-picture puts the call back full screen.
     *
     * PiP was entered *from* the full-screen call, so tapping the floating window has to return the
     * screen the user left. Dropping them into a minimized bar over whatever room they had been
     * reading would be a different screen than the one they shrank.
     */
    @Test
    fun `coming back from picture-in-picture restores the full screen call`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService)
        controller.setInPictureInPicture(true)
        controller.setMaximized(false)
        runCurrent()

        controller.setInPictureInPicture(false)
        runCurrent()

        assertThat(controller.isInPictureInPicture.value).isFalse()
        assertThat(controller.state.value?.isMaximized).isTrue()
    }

    /**
     * The ordering Android 14 enforces: the `mediaProjection` foreground service type has to be
     * claimed *before* the projection is, and claiming happens inside `setScreenShareEnabled`.
     *
     * Getting this backwards is a `SecurityException` from the platform rather than a warning, and it
     * would only ever show up on a device running 14 or later - which is exactly the kind of thing
     * worth pinning down here, where the order is visible.
     */
    @Test
    fun `sharing the screen upgrades the foreground service before claiming the projection`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        // What the call had been asked to do at the moment the service was upgraded. The ordering is
        // only visible from inside that moment: two counters read afterwards would both be non-zero
        // whichever way round it happened.
        var shareCallsWhenProjectingClaimed: Int? = null
        val platform = FakeElementCallPlatform(
            onStartForegroundService = { isProjecting ->
                if (isProjecting) {
                    shareCallsWhenProjectingClaimed = rtcService.lastSession?.lastCall?.screenShareCalls?.size
                }
            },
        )
        val controller = createController(rtcService = rtcService, platform = platform, isScreenSharingEnabled = true)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val call = rtcService.lastSession?.lastCall!!
        assertThat(controller.state.value?.isScreenShareAvailable).isTrue()
        // Everything the connection itself started, so what is left is what sharing did.
        platform.startForegroundServiceProjecting.clear()

        controller.setScreenShareEnabled(MatrixRtcScreenCaptureToken(Intent()))
        runCurrent()

        assertThat(platform.startForegroundServiceProjecting).containsExactly(true)
        // The projection had not been claimed yet when the service took the type. This is the
        // assertion that fails if the two lines are ever swapped.
        assertThat(shareCallsWhenProjectingClaimed).isEqualTo(0)
        assertThat(call.screenShareCalls).containsExactly(true to true)
        assertThat(call.isScreenSharing.value).isTrue()
        assertThat(controller.state.value?.isScreenSharing).isTrue()
    }

    /**
     * Screen sharing is opt-in, and the reason is the manifest: a host that has not declared the
     * `mediaProjection` service type would get a `SecurityException` from the very first line of a
     * share. So with the default options nothing is started - not the service upgrade, not the share -
     * and the snapshot says the control is unavailable, which is what keeps the button off the bar.
     */
    @Test
    fun `sharing the screen is refused unless the host enabled it`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val platform = FakeElementCallPlatform()
        val controller = createController(rtcService = rtcService, platform = platform)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val call = rtcService.lastSession?.lastCall!!
        assertThat(controller.state.value?.isScreenShareAvailable).isFalse()
        platform.startForegroundServiceProjecting.clear()

        controller.setScreenShareEnabled(MatrixRtcScreenCaptureToken(Intent()))
        runCurrent()

        assertThat(platform.startForegroundServiceProjecting).isEmpty()
        assertThat(call.screenShareCalls).isEmpty()
        assertThat(controller.state.value?.isScreenSharing).isFalse()
    }

    /** And the type is given back afterwards, so the screen-recording indicator does not linger. */
    @Test
    fun `stopping the share drops the media projection service type`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val platform = FakeElementCallPlatform()
        val controller = createController(rtcService = rtcService, platform = platform, isScreenSharingEnabled = true)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val call = rtcService.lastSession?.lastCall!!
        controller.setScreenShareEnabled(MatrixRtcScreenCaptureToken(Intent()))
        runCurrent()
        platform.startForegroundServiceProjecting.clear()

        controller.setScreenShareEnabled(token = null)
        runCurrent()

        assertThat(call.isScreenSharing.value).isFalse()
        assertThat(platform.startForegroundServiceProjecting).containsExactly(false)
    }

    /**
     * The user can end a share from the system's cast notification, with the app never asked. The
     * state has to follow that, or the button goes on offering to stop a share that has already
     * stopped.
     */
    @Test
    fun `a share ended from outside the app turns the state off`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService, isScreenSharingEnabled = true)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val call = rtcService.lastSession?.lastCall!!
        call.setScreenShareEnabled(enabled = true, token = MatrixRtcScreenCaptureToken(Intent()))
        runCurrent()
        assertThat(controller.state.value?.isScreenSharing).isTrue()

        call.stopScreenShareExternally()
        runCurrent()

        assertThat(controller.state.value?.isScreenSharing).isFalse()
    }

    /**
     * A DM is one other person being called, so their phone rings. Pinned because the alternative is
     * silent in the worst way: a callee who is never told, on the one call shape where being told is
     * the entire point.
     */
    @Test
    fun `a call in a DM asks the far end to ring`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService, roomIsDm = true, isAudioCall = false)

        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(rtcService.lastNotify?.type).isEqualTo(MatrixRtcNotificationType.RING)
        // The intent rides along, so the callee is told what they are being invited to.
        assertThat(rtcService.lastNotify?.intent).isEqualTo(MatrixRtcCallIntent.VIDEO)
    }

    /**
     * The other half of the rule, and the one with a cost attached: ringing a group room summons
     * everyone in it, so anything that is not a DM only gets a silent notification.
     */
    @Test
    fun `a call in a room notifies without ringing`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService, roomIsDm = false, isAudioCall = true)

        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(rtcService.lastNotify?.type).isEqualTo(MatrixRtcNotificationType.NOTIFY)
        assertThat(rtcService.lastNotify?.intent).isEqualTo(MatrixRtcCallIntent.AUDIO)
    }

    /**
     * A room the host cannot describe in time is treated as a group: an unwanted ring wakes people
     * up, a missing one only makes the call quieter than it should have been. And the join is not
     * held hostage to the answer - the call still connects.
     */
    @Test
    fun `a room that cannot be read in time notifies rather than rings, and the call still joins`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService, roomIsDm = null, isAudioCall = false)

        controller.setMicrophonePermissionGranted(true)
        advanceTimeBy(DefaultElementCallController.ROOM_CONTEXT_TIMEOUT_MS + 1)
        runCurrent()

        assertThat(rtcService.lastNotify?.type).isEqualTo(MatrixRtcNotificationType.NOTIFY)
        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Connected)
        assertThat(controller.state.value?.roomName).isNull()
    }

    /** The room's name, direct flag and members reach the snapshot, which is what the bar and the tiles draw. */
    @Test
    fun `the room context is joined onto the call`() = runTest {
        val roomContextProvider = FakeElementCallRoomContextProvider(
            roomContext = ElementCallRoomContext(displayName = "Paulina", isDm = true, members = emptyMap()),
        )
        val controller = createController(roomContextProvider = roomContextProvider)

        assertThat(controller.state.value?.roomName).isEqualTo("Paulina")
        assertThat(controller.state.value?.isDm).isTrue()

        roomContextProvider.roomContext.value = ElementCallRoomContext(displayName = "Renamed", isDm = true, members = emptyMap())
        runCurrent()

        assertThat(controller.state.value?.roomName).isEqualTo("Renamed")
    }

    /**
     * A real controller over fakes, with a call already started the way the host would.
     *
     * [runCurrent] is what makes that deterministic: `startCall` hands its work to [backgroundScope],
     * so without draining the scheduler here the first thing a test does could arrive before the
     * call exists and be dropped.
     *
     * [roomIsDm] is what the host says about the room, or null for a host that never answers - which
     * is what the notification timeout is for.
     */
    private fun TestScope.createController(
        rtcService: FakeMatrixRtcService = FakeMatrixRtcService(),
        audioDeviceController: FakeCallAudioDeviceController = FakeCallAudioDeviceController(),
        elementCallCompat: MatrixRtcElementCallCompat = MatrixRtcElementCallCompat.OFF,
        platform: FakeElementCallPlatform = FakeElementCallPlatform(),
        audioFocus: FakeAudioFocus = FakeAudioFocus(requestAudioFocusResult = {}, releaseAudioFocusResult = {}),
        lifecycleListener: FakeElementCallLifecycleListener = FakeElementCallLifecycleListener(),
        isAudioCall: Boolean = true,
        roomIsDm: Boolean? = false,
        roomContextProvider: FakeElementCallRoomContextProvider = FakeElementCallRoomContextProvider(
            roomContext = roomIsDm?.let { ElementCallRoomContext(displayName = "A room", isDm = it, members = emptyMap()) },
        ),
        isScreenSharingEnabled: Boolean = false,
    ): DefaultElementCallController {
        val controller = DefaultElementCallController(
            scope = backgroundScope,
            platform = platform,
            rtcService = rtcService,
            audioDeviceController = audioDeviceController,
            audioFocus = audioFocus,
            lifecycleListener = lifecycleListener,
            roomContextProvider = roomContextProvider,
            options = ElementCallOptions(elementCallCompat = elementCallCompat, isScreenSharingEnabled = isScreenSharingEnabled),
        )
        controller.startCall(ElementCallData(roomId = A_ROOM_ID, isAudioCall = isAudioCall))
        runCurrent()
        return controller
    }
}
