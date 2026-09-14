/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import android.content.Intent
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.features.call.api.CallData
import io.element.android.call.impl.NativeCallConnection
import io.element.android.call.impl.NativeCallController
import io.element.android.call.test.FakeCurrentCallTracker
import io.element.android.call.test.FakeMatrixRtcService
import io.element.android.call.test.FakeMatrixRtcServiceProvider
import io.element.android.call.test.FakeNativeCallPlatform
import io.element.android.call.test.FakeRingingCallTracker
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.api.rtc.id.UserId
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.matrix.api.notification.RtcNotificationType
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.FakeMatrixClientProvider
import io.element.android.libraries.matrix.test.room.FakeBaseRoom
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.room.aRoomInfo
import io.element.android.call.api.rtc.MatrixRtcAudioLevel
import io.element.android.call.api.rtc.MatrixRtcCallEvent
import io.element.android.call.api.rtc.MatrixRtcElementCallCompat
import io.element.android.call.api.rtc.MatrixRtcFrameEncryptionDiagnostic
import io.element.android.call.api.rtc.MatrixRtcFrameEncryptionState
import io.element.android.call.api.rtc.MatrixRtcMembership
import io.element.android.call.api.rtc.MatrixRtcParticipant
import io.element.android.call.api.rtc.MatrixRtcScreenCaptureToken
import io.element.android.call.api.rtc.MatrixRtcSpeakingMember
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcStreamState
import io.element.android.call.api.rtc.MatrixRtcTransport
import io.element.android.call.test.audio.FakeAudioFocus
import io.element.android.call.test.audio.FakeCallAudioDeviceController
import io.element.android.call.test.audio.aBluetoothHeadset
import io.element.android.call.test.audio.aSpeaker
import io.element.android.call.test.audio.anEarpiece
import io.element.android.libraries.preferences.test.InMemoryAppPreferencesStore
import io.element.android.services.appnavstate.test.FakeAppForegroundStateService
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.consumeItemsUntilPredicate
import io.element.android.tests.testutils.consumeItemsUntilTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

private val A_ROOM_ID = RoomId("!aRoom:example.org")
private val A_TRANSPORT = MatrixRtcTransport.LiveKit("https://sfu.example.org/jwt")
private val A_RECEIVE_STATS = aReceiveStats(packetsReceived = 1_200, concealedSamples = 24_000)
private const val ANOTHER_REMOTE_MEMBER_ID = "3d7f0c7c6f2a4d0b9e8a1c5b7d6e4f21"
private val A_MEMBERSHIP = MatrixRtcMembership(
    memberId = "aMemberId",
    userId = UserId("@alice:example.org"),
    deviceId = "ADEVICEID",
    application = "m.call",
)

class NativeCallPresenterTest {
    @get:Rule val warmUpRule = WarmUpRule()

    @Test
    fun `initial state waits for the microphone permission`() = runTest {
        val presenter = createPresenter()

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            val initialState = awaitItem()
            assertThat(initialState.connection).isEqualTo(NativeCallConnection.RequestingPermission)
            assertThat(initialState.isMicrophonePermissionGranted).isFalse()
            assertThat(initialState.memberCount).isEqualTo(0)
        }
    }

    @Test
    fun `denying the microphone fails the call rather than joining silently`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val presenter = createPresenter(rtcService = rtcService)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(false))

            val states = consumeItemsUntilPredicate { it.connection is NativeCallConnection.Failed }
            assertThat(states.last().connection).isInstanceOf(NativeCallConnection.Failed::class.java)
            assertThat(rtcService.joinCallCount).isEqualTo(0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `granting the microphone joins and connects media`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val presenter = createPresenter(rtcService = rtcService)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))

            val states = consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }
            assertThat(states.last().isMicrophonePermissionGranted).isTrue()
            assertThat(rtcService.joinCallCount).isEqualTo(1)
            assertThat(rtcService.lastSession?.connectMediaCount).isEqualTo(1)
            assertThat(rtcService.lastSession?.lastCall?.publishMicrophoneCount).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The roster used to be left unsubscribed in this mode, because no membership reached the core to
     * project. Room state is readable now and [RoomStateFeeder] feeds it, so the count is as real here
     * as anywhere - and a mode-specific branch that skipped the subscription would report a call with
     * nobody in it while media and key distribution both worked.
     */
    @Test
    fun `a state-carried call reports the core's member count too`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val presenter = createPresenter(
            rtcService = rtcService,
            elementCallCompat = MatrixRtcElementCallCompat.STATE_EVENTS,
        )

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }

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

    @Test
    fun `a spec call still reports the core's member count`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val presenter = createPresenter(rtcService = rtcService)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }

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
     * Temporary, with the pin it tests: while the app builds against the released Rust SDK only the
     * state-event generation of Element Call can be bridged, so the developer setting is read but not
     * obeyed. Restore the two tests this replaced when the pin goes (see `libraries/rustrtc/FEEDBACK.md`,
     * "Widget-driver stopgap").
     */
    @Test
    fun `a call joins in the state-event Element Call compatibility whatever the developer setting says`() = runTest {
        MatrixRtcElementCallCompat.entries.forEach { preferred ->
            val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
            val presenter = createPresenter(
                rtcService = rtcService,
                elementCallCompat = preferred,
            )

            moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
                awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))

                consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }
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
        val presenter = createPresenter(rtcService = rtcService)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))

            val states = consumeItemsUntilPredicate { it.connection is NativeCallConnection.Failed }
            val failure = states.last().connection as NativeCallConnection.Failed
            assertThat(failure.message).contains("LiveKit")
            assertThat(rtcService.joinCallCount).isEqualTo(0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hanging up disconnects the media, leaves the session and closes the screen`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        var closed = false
        val presenter = createPresenter(rtcService = rtcService, onClose = { closed = true })

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            val connected = consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }.last()

            connected.eventSink(NativeCallEvent.HangUp)
            // Hanging up emits no new state, it just tears down and closes, so let the launched
            // work drain rather than waiting for an item that never arrives.
            consumeItemsUntilTimeout()

            assertThat(rtcService.lastSession?.lastCall?.disconnectCount).isEqualTo(1)
            assertThat(rtcService.lastSession?.leaveCount).isEqualTo(1)
            assertThat(closed).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggling mute is forwarded to the call`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val presenter = createPresenter(rtcService = rtcService)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            val connected = consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }.last()

            connected.eventSink(NativeCallEvent.ToggleMicrophoneMuted)

            val muted = consumeItemsUntilPredicate { it.isMicrophoneMuted }.last()
            assertThat(muted.isMicrophoneMuted).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `audio levels and active speakers reach the state`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val presenter = createPresenter(rtcService = rtcService)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }

            val call = rtcService.lastSession?.lastCall!!
            call.audioLevels.value = mapOf("aRemoteMemberId" to MatrixRtcAudioLevel(level = 0.5f, frameCount = 42))
            call.emit(MatrixRtcCallEvent.ActiveSpeakers(listOf(MatrixRtcSpeakingMember("aRemoteMemberId", level = 0.8f))))

            // Levels are sampled and speakers are not, so the two need not arrive together.
            val state = consumeItemsUntilPredicate { it.activeSpeakerIds.isNotEmpty() && it.audioLevels.isNotEmpty() }.last()
            assertThat(state.audioLevels["aRemoteMemberId"]).isEqualTo(MatrixRtcAudioLevel(level = 0.5f, frameCount = 42))
            assertThat(state.activeSpeakerIds).containsExactly("aRemoteMemberId")
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
        val presenter = createPresenter(rtcService = rtcService)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }
            val call = rtcService.lastSession?.lastCall!!

            // Two readings with no time passing between them: a window's worth of a meter.
            call.audioLevels.value = mapOf("aRemoteMemberId" to MatrixRtcAudioLevel(level = 0.1f, frameCount = 1))
            call.audioLevels.value = mapOf("aRemoteMemberId" to MatrixRtcAudioLevel(level = 0.9f, frameCount = 2))
            advanceTimeBy(NativeCallController.AUDIO_LEVEL_SAMPLE_MS + 1)
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
        val presenter = createPresenter(rtcService = rtcService)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }

            val call = rtcService.lastSession?.lastCall!!
            call.receiveStats.value = mapOf("aRemoteMemberId" to A_RECEIVE_STATS)
            call.emit(
                MatrixRtcCallEvent.FrameEncryption(
                    memberId = "aRemoteMemberId",
                    state = MatrixRtcFrameEncryptionState.MISSING_KEY,
                    diagnostic = MatrixRtcFrameEncryptionDiagnostic.NoKeyInstalled,
                )
            )

            val state = consumeItemsUntilPredicate { it.frameEncryption.isNotEmpty() }.last()
            assertThat(state.receiveStats["aRemoteMemberId"]).isEqualTo(A_RECEIVE_STATS)
            assertThat(state.frameEncryption["aRemoteMemberId"]).isEqualTo(MatrixRtcFrameEncryptionState.MISSING_KEY)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggling the test tone is forwarded to the call`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val presenter = createPresenter(rtcService = rtcService)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            val connected = consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }.last()

            connected.eventSink(NativeCallEvent.ToggleAudioTestTone)

            val toneOn = consumeItemsUntilPredicate { it.isAudioTestToneEnabled }.last()
            assertThat(toneOn.isAudioTestToneEnabled).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `connecting takes the audio route and offers every device`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val audioDevices = FakeCallAudioDeviceController(initialDevices = listOf(anEarpiece(), aSpeaker()))
        val presenter = createPresenter(rtcService = rtcService, audioDeviceController = audioDevices)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            val connected = consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }.last()

            // The route is taken, and it is chosen rather than left to whatever the platform had:
            // a voice call that starts on the loudspeaker is startling in a way the reverse is not.
            assertThat(audioDevices.startCount).isEqualTo(1)
            assertThat(audioDevices.lastPreferLoudspeaker).isFalse()
            assertThat(connected.selectedAudioDevice).isEqualTo(anEarpiece())
            assertThat(connected.audioDevices).containsExactly(anEarpiece(), aSpeaker()).inOrder()

            connected.eventSink(NativeCallEvent.SelectAudioDevice(aSpeaker()))

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
        val presenter = createPresenter(rtcService = rtcService, audioDeviceController = audioDevices, isAudioCall = false)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            val connected = consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }.last()

            assertThat(audioDevices.lastPreferLoudspeaker).isTrue()
            assertThat(connected.selectedAudioDevice).isEqualTo(aSpeaker())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a video call still prefers a headset over the loudspeaker`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val audioDevices = FakeCallAudioDeviceController(initialDevices = listOf(aBluetoothHeadset(), anEarpiece(), aSpeaker()))
        val presenter = createPresenter(rtcService = rtcService, audioDeviceController = audioDevices, isAudioCall = false)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            val connected = consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }.last()

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
        val presenter = createPresenter(rtcService = rtcService, audioDeviceController = audioDevices)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }

            audioDevices.devices.value = listOf(aBluetoothHeadset(), anEarpiece(), aSpeaker())

            val state = consumeItemsUntilPredicate { it.audioDevices.size == 3 }.last()
            assertThat(state.audioDevices).contains(aBluetoothHeadset())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Hanging up releases it, and *only* hanging up does.
     *
     * Leaving the composition used to be what released the route, because the call lived in the
     * composition. It does not any more - the call outlives every screen that draws it - so a route
     * handed back on composition exit would cut the audio of a call the user had merely minimized.
     * Communication mode is device-wide state, so the other half of this matters just as much:
     * releasing has to actually happen on hangup, or every other app on the device keeps routing to
     * the earpiece.
     */
    @Test
    fun `hanging up hands the audio route back, and leaving the screen does not`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val audioDevices = FakeCallAudioDeviceController()
        val presenter = createPresenter(rtcService = rtcService, audioDeviceController = audioDevices)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            val connected = consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }.last()
            assertThat(audioDevices.stopCount).isEqualTo(0)

            connected.eventSink(NativeCallEvent.HangUp)
            consumeItemsUntilTimeout()
            assertThat(audioDevices.stopCount).isEqualTo(1)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `leaving the screen leaves the call running`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val audioDevices = FakeCallAudioDeviceController()
        val platform = FakeNativeCallPlatform()
        val presenter = createPresenter(rtcService = rtcService, audioDeviceController = audioDevices, platform = platform)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }
            cancelAndIgnoreRemainingEvents()
        }

        // Nothing was torn down by the composition ending: the route is still held, the foreground
        // service is still up, and the session was never left. This is what makes a minimized call
        // possible at all.
        assertThat(audioDevices.stopCount).isEqualTo(0)
        assertThat(platform.stopForegroundServiceCount).isEqualTo(0)
        assertThat(rtcService.lastSession?.leaveCount).isEqualTo(0)
    }

    /**
     * Minimizing is a change of view, not a change to the call.
     *
     * The distinction is the entire point of hosting the call outside a screen: everything a user
     * would notice going wrong here - audio stopping, the session being left, the route handed back -
     * is what happens if minimizing is confused with leaving.
     */
    @Test
    fun `minimizing keeps the call running`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val audioDevices = FakeCallAudioDeviceController()
        val platform = FakeNativeCallPlatform()
        val controller = createController(rtcService = rtcService, audioDeviceController = audioDevices, platform = platform)
        val presenter = createPresenter(controller = controller)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            val connected = consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }.last()
            assertThat(controller.state.value?.isMaximized).isTrue()

            connected.eventSink(NativeCallEvent.Minimize)
            consumeItemsUntilTimeout()

            assertThat(controller.state.value?.isMaximized).isFalse()
            // Still connected, still holding the route, still a member of the session.
            assertThat(controller.state.value?.connection).isEqualTo(NativeCallConnection.Connected)
            assertThat(audioDevices.stopCount).isEqualTo(0)
            assertThat(platform.stopForegroundServiceCount).isEqualTo(0)
            assertThat(rtcService.lastSession?.leaveCount).isEqualTo(0)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a call starts maximized and can be restored after minimizing`() = runTest {
        val controller = createController(rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT)))
        val presenter = createPresenter(controller = controller)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            val connected = consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }.last()

            connected.eventSink(NativeCallEvent.Minimize)
            consumeItemsUntilTimeout()
            assertThat(controller.state.value?.isMaximized).isFalse()

            // What tapping the minimized bar does.
            controller.setMaximized(true)
            assertThat(controller.state.value?.isMaximized).isTrue()

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * A video call starts with the camera on.
     *
     * `CallData.isAudioCall` is carried all the way from the caller's `m.call.intent`, through the
     * push, the ringing notification and the answer - and was then dropped on the floor here, so
     * every call started audio-only however it was placed or answered.
     */
    @Test
    fun `a video call turns the camera on once connected`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService, isAudioCall = false)
        val presenter = createPresenter(controller = controller)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            // The host asks for the camera up front on a video call, so it is granted before media
            // connects. This is the ordering where the controller has to act on it itself.
            awaitItem().eventSink(NativeCallEvent.SetCameraPermissionGranted(true))
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }
            consumeItemsUntilTimeout()

            assertThat(rtcService.lastSession?.lastCall?.cameraEnabledCalls).contains(true)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an audio call leaves the camera off`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val controller = createController(rtcService = rtcService, isAudioCall = true)
        val presenter = createPresenter(controller = controller)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetCameraPermissionGranted(true))
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }
            consumeItemsUntilTimeout()

            // Granting the camera is not the same as wanting it on: an audio call that answered the
            // permission dialog must still start with the camera off.
            assertThat(rtcService.lastSession?.lastCall?.isCameraEnabled?.value).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Answering has to tell the ringing machinery it was answered.
     *
     * Nothing about the call itself depends on this, which is exactly why it is easy to leave out
     * and hard to spot in code review - the failure is entirely in the surrounding experience: the
     * ringtone keeps playing over the connected call, the full-screen incoming UI stays up, and 90
     * seconds later a missed-call notification arrives for a call that was answered.
     */
    @Test
    fun `connecting stops the ring, and ending clears the active call`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val ringingCallTracker = FakeRingingCallTracker()
        val controller = createController(rtcService = rtcService, ringingCallTracker = ringingCallTracker)
        val presenter = createPresenter(controller = controller)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            val connected = consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }.last()

            assertThat(ringingCallTracker.joined.map { it.roomId }).containsExactly(A_ROOM_ID)
            assertThat(ringingCallTracker.ended).isEmpty()

            connected.eventSink(NativeCallEvent.HangUp)
            consumeItemsUntilTimeout()

            assertThat(ringingCallTracker.ended.map { it.roomId }).containsExactly(A_ROOM_ID)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Nobody is drawn twice: the spotlight and the strip never show the same member.
     *
     * Both halves of what this cost are worth keeping pinned. Visually it reads as a glitch - the
     * same face above itself. On the video path it was a crash: two tiles meant two collectors on a
     * cold flow that opens one stream each.
     */
    @Test
    fun `the spotlighted member is not drawn again in the strip`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val presenter = createPresenter(rtcService = rtcService)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }
            val call = rtcService.lastSession?.lastCall!!

            call.participants.value = listOf(
                aCameraParticipant(A_LOCAL_MEMBER_ID, isLocal = true, isCameraMuted = false),
                aCameraParticipant(A_REMOTE_MEMBER_ID, isLocal = false, isCameraMuted = false),
            )
            val state = consumeItemsUntilPredicate { it.tiles.size == 2 }.last()

            // The remote is spotlighted, so only we are left in the strip.
            assertThat(state.spotlightParticipant?.memberId).isEqualTo(A_REMOTE_MEMBER_ID)
            assertThat(state.stripParticipants.map { it.memberId }).containsExactly(A_LOCAL_MEMBER_ID)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Alone in a call, there is nobody to spotlight - and we must not spotlight ourselves, which
     * would draw us both above and inside the strip.
     */
    @Test
    fun `a call with nobody else in it has no spotlight`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val presenter = createPresenter(rtcService = rtcService)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }
            val call = rtcService.lastSession?.lastCall!!

            call.participants.value = listOf(aCameraParticipant(A_LOCAL_MEMBER_ID, isLocal = true, isCameraMuted = false))
            val state = consumeItemsUntilPredicate { it.tiles.isNotEmpty() }.last()

            assertThat(state.spotlightParticipant).isNull()
            assertThat(state.stripParticipants.map { it.memberId }).containsExactly(A_LOCAL_MEMBER_ID)

            cancelAndIgnoreRemainingEvents()
        }
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
        val presenter = createPresenter(controller = controller)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }
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
            advanceTimeBy(NativeCallController.VIDEO_STREAM_LINGER_MS + 1)
            runCurrent()
            assertThat(call.openVideoStreamsFor(A_REMOTE_MEMBER_ID)).isEqualTo(0)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The spotlight does not follow every active-speaker event.
     *
     * Those arrive several times a second in any real conversation. Following them exactly makes the
     * big tile unwatchable, and - because the strip excludes whoever is spotlighted - disposes one
     * video tile and composes another on each change. Doing that a few times a second churned enough
     * renderer and decoder threads that libwebrtc aborted the process on a stale JNI thread-local,
     * roughly a thousand threads into a call with three people talking.
     */
    @Test
    fun `two people talking over each other do not flap the spotlight`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val platform = FakeNativeCallPlatform()
        val controller = createController(rtcService = rtcService, platform = platform)
        val presenter = createPresenter(controller = controller)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }
            val call = rtcService.lastSession?.lastCall!!

            call.participants.value = listOf(
                aCameraParticipant(A_LOCAL_MEMBER_ID, isLocal = true, isCameraMuted = false),
                aCameraParticipant(A_REMOTE_MEMBER_ID, isLocal = false, isCameraMuted = false),
                aCameraParticipant(A_STALE_MEMBER_ID, isLocal = false, isCameraMuted = false),
            )
            consumeItemsUntilPredicate { it.tiles.size == 3 }
            val firstSpotlight = controller.state.value?.spotlightMemberId

            // The two remotes trade the floor several times, as they do when talking over one
            // another. The clock does not move, so none of it clears the dwell.
            repeat(6) { index ->
                val speaker = if (index % 2 == 0) A_REMOTE_MEMBER_ID else A_STALE_MEMBER_ID
                call.emit(MatrixRtcCallEvent.ActiveSpeakers(listOf(MatrixRtcSpeakingMember(speaker, level = 0.8f))))
            }
            consumeItemsUntilTimeout()

            assertThat(controller.state.value?.spotlightMemberId).isEqualTo(firstSpotlight)

            cancelAndIgnoreRemainingEvents()
        }
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
        val presenter = createPresenter(controller = controller)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }
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
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The camera is asked for on the tap, not up front with the microphone - so tapping without it
     * must ask and nothing more. Enabling anyway would open a camera we have no right to and fail
     * asynchronously somewhere in Camera2, with the UI already showing the camera as on.
     */
    @Test
    fun `tapping the camera without permission asks for it and starts nothing`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        var requestCount = 0
        val presenter = createPresenter(rtcService = rtcService, onRequestCameraPermission = { requestCount++ })

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            val connected = consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }.last()
            assertThat(connected.isCameraPermissionGranted).isFalse()

            connected.eventSink(NativeCallEvent.ToggleCamera)
            consumeItemsUntilTimeout()

            assertThat(requestCount).isEqualTo(1)
            assertThat(rtcService.lastSession?.lastCall?.cameraEnabledCalls).isEmpty()

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The only way to reach this is having just tapped the camera button, so making the user tap it
     * again after saying yes would be a strange reward for granting it.
     */
    @Test
    fun `granting the camera permission turns the camera on`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val presenter = createPresenter(rtcService = rtcService)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            val connected = consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }.last()

            connected.eventSink(NativeCallEvent.SetCameraPermissionGranted(true))
            val withCamera = consumeItemsUntilPredicate { it.isCameraEnabled }.last()

            assertThat(withCamera.isCameraPermissionGranted).isTrue()
            assertThat(rtcService.lastSession?.lastCall?.cameraEnabledCalls).containsExactly(true)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggling the camera off once it is on stops it`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val presenter = createPresenter(rtcService = rtcService)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }
                .last()
                .eventSink(NativeCallEvent.SetCameraPermissionGranted(true))
            val withCamera = consumeItemsUntilPredicate { it.isCameraEnabled }.last()

            withCamera.eventSink(NativeCallEvent.ToggleCamera)
            val withoutCamera = consumeItemsUntilPredicate { !it.isCameraEnabled }.last()

            assertThat(withoutCamera.isCameraEnabled).isFalse()
            assertThat(rtcService.lastSession?.lastCall?.cameraEnabledCalls).containsExactly(true, false).inOrder()

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The camera side is asynchronous - it has to close one device and open another - so the state
     * has to come back from the call rather than being assumed at the tap.
     */
    @Test
    fun `switching the camera follows what the call reports rather than the request`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val presenter = createPresenter(rtcService = rtcService)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            val connected = consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }.last()
            // Front facing before the camera has ever been opened, which is the side it starts on.
            assertThat(connected.isFrontCamera).isTrue()

            connected.eventSink(NativeCallEvent.SwitchCamera)
            val switched = consumeItemsUntilPredicate { !it.isFrontCamera }.last()

            assertThat(switched.isFrontCamera).isFalse()
            assertThat(rtcService.lastSession?.lastCall?.switchCameraCount).isEqualTo(1)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Which members get a tile, and the rule that decides it. A muted camera track still exists on
     * the roster, so keying off the stream's presence alone would leave a frozen tile after someone
     * turns their camera off - and would open a video stream to decode frames nobody is sending.
     */
    @Test
    fun `only members publishing an unmuted camera get video`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val presenter = createPresenter(rtcService = rtcService)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }
            val call = rtcService.lastSession?.lastCall!!

            call.participants.value = listOf(
                aCameraParticipant(A_LOCAL_MEMBER_ID, isLocal = true, isCameraMuted = false),
                aCameraParticipant(A_REMOTE_MEMBER_ID, isLocal = false, isCameraMuted = true),
                aRemoteParticipant().copy(memberId = A_STALE_MEMBER_ID),
            )
            val state = consumeItemsUntilPredicate { it.videoFrames.isNotEmpty() }.last()

            // The unmuted camera only: not the muted one, and not the audio-only member.
            assertThat(state.videoFrames.keys).containsExactly(A_LOCAL_MEMBER_ID)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * A member sharing their screen is two tiles with two independent video streams, and the two must
     * not collide.
     *
     * Keyed by member id alone - which is how this worked before screen share existed - the person and
     * their screen share one key, so Compose reuses one composable for both and the second stream to
     * be asked for silently gets the first one's frames. The same collision on the RTC side is what
     * crashed the core inside `VideoSinkWrapper::on_frame`.
     */
    @Test
    fun `a member sharing their screen gets a tile and a stream for each`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val presenter = createPresenter(rtcService = rtcService)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }
            val call = rtcService.lastSession?.lastCall!!

            call.participants.value = listOf(aSharingParticipant(A_REMOTE_MEMBER_ID))
            val state = consumeItemsUntilPredicate { it.tiles.size == 2 }.last()

            // Two tiles, two distinct keys, two frame streams.
            assertThat(state.tiles.map { it.tileId })
                .containsExactly(A_REMOTE_MEMBER_ID, "$A_REMOTE_MEMBER_ID#SCREEN_SHARE")
            assertThat(state.videoFrames.keys)
                .containsExactly(A_REMOTE_MEMBER_ID, "$A_REMOTE_MEMBER_ID#SCREEN_SHARE")

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * A shared screen takes the spotlight, and the person sharing it stays in the strip.
     *
     * The screen beats the active speaker outright rather than competing with them: somebody shares a
     * screen in order for it to be looked at, and a spotlight that flicked away every time they spoke
     * would be worse than one that never moved.
     */
    @Test
    fun `a shared screen takes the spotlight and its owner stays in the strip`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val presenter = createPresenter(rtcService = rtcService)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }
            val call = rtcService.lastSession?.lastCall!!

            call.participants.value = listOf(aSharingParticipant(A_REMOTE_MEMBER_ID))
            val state = consumeItemsUntilPredicate { it.spotlightParticipant?.isScreenShare == true }.last()

            assertThat(state.spotlightParticipant?.tileId).isEqualTo("$A_REMOTE_MEMBER_ID#SCREEN_SHARE")
            // The sharer is still shown - only their screen was promoted, not them.
            assertThat(state.stripParticipants.map { it.tileId }).containsExactly(A_REMOTE_MEMBER_ID)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Our own screen is published but never drawn: nobody needs to be shown their own screen. */
    @Test
    fun `our own screen share does not become a tile`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val presenter = createPresenter(rtcService = rtcService)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }
            val call = rtcService.lastSession?.lastCall!!

            call.participants.value = listOf(aSharingParticipant(A_LOCAL_MEMBER_ID, isLocal = true))
            val state = consumeItemsUntilPredicate { it.tiles.isNotEmpty() }.last()

            assertThat(state.tiles.map { it.tileId }).containsExactly(A_LOCAL_MEMBER_ID)
            assertThat(state.videoFrames.keys).doesNotContain("$A_LOCAL_MEMBER_ID#SCREEN_SHARE")

            cancelAndIgnoreRemainingEvents()
        }
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
        val appForegroundStateService = FakeAppForegroundStateService()
        val controller = createController(
            rtcService = rtcService,
            audioDeviceController = audioDeviceController,
            appForegroundStateService = appForegroundStateService,
        )
        val presenter = createPresenter(
            rtcService = rtcService,
            audioDeviceController = audioDeviceController,
            appForegroundStateService = appForegroundStateService,
            controller = controller,
        )

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }
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
            appForegroundStateService.givenIsInForeground(false)
            runCurrent()
            assertThat(audioDeviceController.proximityBlankingAllowed).isFalse()

            cancelAndIgnoreRemainingEvents()
        }
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
        val presenter = createPresenter(rtcService = rtcService, controller = controller)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }
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

            cancelAndIgnoreRemainingEvents()
        }
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
        val rtcServiceRef = rtcService
        val platform = FakeNativeCallPlatform(
            onStartForegroundService = { isProjecting ->
                if (isProjecting) {
                    shareCallsWhenProjectingClaimed = rtcServiceRef.lastSession?.lastCall?.screenShareCalls?.size
                }
            },
        )
        val controller = createController(rtcService = rtcService, platform = platform)
        val presenter = createPresenter(rtcService = rtcService, platform = platform, controller = controller)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }
            val call = rtcService.lastSession?.lastCall!!
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

            cancelAndIgnoreRemainingEvents()
        }
    }

    /** And the type is given back afterwards, so the screen-recording indicator does not linger. */
    @Test
    fun `stopping the share drops the media projection service type`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val platform = FakeNativeCallPlatform()
        val controller = createController(rtcService = rtcService, platform = platform)
        val presenter = createPresenter(rtcService = rtcService, platform = platform, controller = controller)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }
            val call = rtcService.lastSession?.lastCall!!
            controller.setScreenShareEnabled(MatrixRtcScreenCaptureToken(Intent()))
            runCurrent()
            platform.startForegroundServiceProjecting.clear()

            controller.setScreenShareEnabled(token = null)
            runCurrent()

            assertThat(call.isScreenSharing.value).isFalse()
            assertThat(platform.startForegroundServiceProjecting).containsExactly(false)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The user can end a share from the system's cast notification, with the app never asked. The
     * state has to follow that, or the button goes on offering to stop a share that has already
     * stopped.
     */
    @Test
    fun `a share ended from outside the app turns the state off`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val presenter = createPresenter(rtcService = rtcService)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }
            val call = rtcService.lastSession?.lastCall!!
            call.setScreenShareEnabled(enabled = true, token = MatrixRtcScreenCaptureToken(Intent()))
            consumeItemsUntilPredicate { it.isScreenSharing }

            call.stopScreenShareExternally()

            assertThat(consumeItemsUntilPredicate { !it.isScreenSharing }.last().isScreenSharing).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Compose keys the tile's collection on the flow, so a fresh instance per composition would
     * reopen the video stream - and the core would restart decoding - on every recomposition.
     */
    @Test
    fun `a member keeps the same video flow across recompositions`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val presenter = createPresenter(rtcService = rtcService)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }
            val call = rtcService.lastSession?.lastCall!!

            call.participants.value = listOf(aCameraParticipant(A_LOCAL_MEMBER_ID, isLocal = true, isCameraMuted = false))
            val first = consumeItemsUntilPredicate { it.videoFrames.isNotEmpty() }.last()
            // Force more recompositions without changing who has video.
            call.audioLevels.value = mapOf(A_LOCAL_MEMBER_ID to MatrixRtcAudioLevel(level = 0.4f, frameCount = 10))
            val later = consumeItemsUntilTimeout().last()

            assertThat(later.videoFrames[A_LOCAL_MEMBER_ID]).isSameInstanceAs(first.videoFrames[A_LOCAL_MEMBER_ID])

            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun aCameraParticipant(memberId: String, isLocal: Boolean, isCameraMuted: Boolean) = MatrixRtcParticipant(
        memberId = memberId,
        userId = UserId("@someone:example.org"),
        deviceId = "ADEVICEID",
        isLocal = isLocal,
        isReachable = true,
        streams = listOf(
            MatrixRtcStreamState(MatrixRtcStreamKind.MICROPHONE, isMuted = false),
            MatrixRtcStreamState(MatrixRtcStreamKind.CAMERA, isMuted = isCameraMuted),
        ),
    )

    /** Someone with their camera on who is also sharing their screen: two video streams, one member. */
    private fun aSharingParticipant(memberId: String, isLocal: Boolean = false) = MatrixRtcParticipant(
        memberId = memberId,
        userId = UserId("@someone:example.org"),
        deviceId = "ADEVICEID",
        isLocal = isLocal,
        isReachable = true,
        streams = listOf(
            MatrixRtcStreamState(MatrixRtcStreamKind.MICROPHONE, isMuted = false),
            MatrixRtcStreamState(MatrixRtcStreamKind.CAMERA, isMuted = false),
            MatrixRtcStreamState(MatrixRtcStreamKind.SCREEN_SHARE, isMuted = false),
        ),
    )

    /**
     * A DM is one other person being called, so their phone rings. Pinned because the alternative is
     * silent in the worst way: a callee who is never told, on the one call shape where being told is
     * the entire point.
     */
    @Test
    fun `a call in a DM asks the far end to ring`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val presenter = createPresenter(rtcService = rtcService, roomIsDm = true, isAudioCall = false)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }

            assertThat(rtcService.lastNotify?.type).isEqualTo(RtcNotificationType.RING)
            // The intent rides along, so the callee is told what they are being invited to.
            assertThat(rtcService.lastNotify?.intent).isEqualTo(CallIntent.VIDEO)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The other half of the rule, and the one with a cost attached: ringing a group room summons
     * everyone in it, so anything that is not a DM only gets a silent notification.
     */
    @Test
    fun `a call in a room notifies without ringing`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val presenter = createPresenter(rtcService = rtcService, roomIsDm = false, isAudioCall = true)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }

            assertThat(rtcService.lastNotify?.type).isEqualTo(RtcNotificationType.NOTIFY)
            assertThat(rtcService.lastNotify?.intent).isEqualTo(CallIntent.AUDIO)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * A DM with the two of us in it is drawn one-to-one: the room says it is a DM, and there are
     * exactly two camera tiles. Both halves come from different places - the room and the media
     * roster - and this is where they meet.
     */
    @Test
    fun `a DM call with one other person lays out one to one`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val presenter = createPresenter(rtcService = rtcService, roomIsDm = true, isAudioCall = false)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }
            val call = rtcService.lastSession?.lastCall!!

            call.participants.value = listOf(
                aCameraParticipant(A_LOCAL_MEMBER_ID, isLocal = true, isCameraMuted = false),
                aCameraParticipant(A_REMOTE_MEMBER_ID, isLocal = false, isCameraMuted = false),
            )
            val state = consumeItemsUntilPredicate { it.tiles.size == 2 && it.isDm }.last()

            assertThat(state.layout).isEqualTo(CallLayout.OneToOne)
            // The other person is the one who fills the screen, never us.
            assertThat(state.spotlightParticipant?.memberId).isEqualTo(A_REMOTE_MEMBER_ID)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /** A third person has nowhere to go in a two-tile layout, so a DM they have joined is drawn as a group. */
    @Test
    fun `a DM call with a third participant lays out as a group`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val presenter = createPresenter(rtcService = rtcService, roomIsDm = true, isAudioCall = false)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }
            val call = rtcService.lastSession?.lastCall!!

            call.participants.value = listOf(
                aCameraParticipant(A_LOCAL_MEMBER_ID, isLocal = true, isCameraMuted = false),
                aCameraParticipant(A_REMOTE_MEMBER_ID, isLocal = false, isCameraMuted = false),
                aCameraParticipant(ANOTHER_REMOTE_MEMBER_ID, isLocal = false, isCameraMuted = false),
            )
            val state = consumeItemsUntilPredicate { it.tiles.size == 3 && it.isDm }.last()

            assertThat(state.layout).isEqualTo(CallLayout.Group)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /** A shared screen is a third tile, and the group layout is the one that knows to spotlight it. */
    @Test
    fun `a DM call where the other person shares their screen lays out as a group`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val presenter = createPresenter(rtcService = rtcService, roomIsDm = true, isAudioCall = false)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }
            val call = rtcService.lastSession?.lastCall!!

            call.participants.value = listOf(
                aCameraParticipant(A_LOCAL_MEMBER_ID, isLocal = true, isCameraMuted = false),
                aSharingParticipant(A_REMOTE_MEMBER_ID),
            )
            val state = consumeItemsUntilPredicate { it.tiles.size == 3 && it.isDm }.last()

            assertThat(state.layout).isEqualTo(CallLayout.Group)
            assertThat(state.spotlightParticipant?.isScreenShare).isTrue()

            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Two people in a room that is not a DM are still a group: the layout follows the room, not the head count. */
    @Test
    fun `a call in a room with one other person lays out as a group`() = runTest {
        val rtcService = FakeMatrixRtcService(transports = listOf(A_TRANSPORT))
        val presenter = createPresenter(rtcService = rtcService, roomIsDm = false, isAudioCall = false)

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            awaitItem().eventSink(NativeCallEvent.SetMicrophonePermissionGranted(true))
            consumeItemsUntilPredicate { it.connection == NativeCallConnection.Connected }
            val call = rtcService.lastSession?.lastCall!!

            call.participants.value = listOf(
                aCameraParticipant(A_LOCAL_MEMBER_ID, isLocal = true, isCameraMuted = false),
                aCameraParticipant(A_REMOTE_MEMBER_ID, isLocal = false, isCameraMuted = false),
            )
            val state = consumeItemsUntilPredicate { it.tiles.size == 2 }.last()

            assertThat(state.isDm).isFalse()
            assertThat(state.layout).isEqualTo(CallLayout.Group)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * A real [NativeCallController] over fakes, with a call already started the way the host would.
     *
     * The controller rather than a fake of it, because the behaviour under test - joining, connecting
     * media, fanning the call's flows into state - all lives there now. Faking it would leave these
     * tests asserting against a hand-written script of the thing they are meant to check.
     *
     * [runCurrent] is what makes that deterministic: `startCall` hands its work to [backgroundScope],
     * so without draining the scheduler here the first event a test sends could arrive before the
     * call exists and be dropped.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.createController(
        rtcService: FakeMatrixRtcService = FakeMatrixRtcService(),
        audioDeviceController: FakeCallAudioDeviceController = FakeCallAudioDeviceController(),
        elementCallCompat: MatrixRtcElementCallCompat = MatrixRtcElementCallCompat.OFF,
        platform: FakeNativeCallPlatform = FakeNativeCallPlatform(),
        currentCallTracker: FakeCurrentCallTracker = FakeCurrentCallTracker(),
        ringingCallTracker: FakeRingingCallTracker = FakeRingingCallTracker(),
        appForegroundStateService: FakeAppForegroundStateService = FakeAppForegroundStateService(),
        isAudioCall: Boolean = true,
        // Null leaves the room unreadable, which is what most of these tests want: the controller then
        // skips observing it, and they can assert on call state without a room's name and members
        // arriving underneath them. The two notification tests are the ones that need a real answer.
        roomIsDm: Boolean? = null,
    ): NativeCallController {
        val client = FakeMatrixClient(sessionId = A_SESSION_ID).apply {
            if (roomIsDm != null) {
                givenGetRoomResult(
                    A_ROOM_ID,
                    FakeJoinedRoom(
                        baseRoom = FakeBaseRoom(
                            initialRoomInfo = aRoomInfo(isDm = roomIsDm),
                            updateMembersResult = { },
                        )
                    ),
                )
            }
        }
        val controller = NativeCallController(
            appCoroutineScope = backgroundScope,
            platform = platform,
            matrixClientProvider = FakeMatrixClientProvider { Result.success(client) },
            rtcServiceProvider = FakeMatrixRtcServiceProvider(rtcService),
            audioDeviceController = audioDeviceController,
            appPreferencesStore = InMemoryAppPreferencesStore(nativeCallElementCallCompat = elementCallCompat),
            currentCallTracker = currentCallTracker,
            ringingCallTracker = ringingCallTracker,
            appForegroundStateService = appForegroundStateService,
            audioFocus = FakeAudioFocus(
                requestAudioFocusResult = { _, _ -> },
                releaseAudioFocusResult = {},
            ),
        )
        controller.startCall(CallData(sessionId = A_SESSION_ID, roomId = A_ROOM_ID, isAudioCall = isAudioCall))
        runCurrent()
        return controller
    }

    private fun TestScope.createPresenter(
        rtcService: FakeMatrixRtcService = FakeMatrixRtcService(),
        audioDeviceController: FakeCallAudioDeviceController = FakeCallAudioDeviceController(),
        elementCallCompat: MatrixRtcElementCallCompat = MatrixRtcElementCallCompat.OFF,
        platform: FakeNativeCallPlatform = FakeNativeCallPlatform(),
        currentCallTracker: FakeCurrentCallTracker = FakeCurrentCallTracker(),
        ringingCallTracker: FakeRingingCallTracker = FakeRingingCallTracker(),
        appForegroundStateService: FakeAppForegroundStateService = FakeAppForegroundStateService(),
        isAudioCall: Boolean = true,
        roomIsDm: Boolean? = null,
        controller: NativeCallController = createController(
            rtcService = rtcService,
            audioDeviceController = audioDeviceController,
            elementCallCompat = elementCallCompat,
            platform = platform,
            currentCallTracker = currentCallTracker,
            ringingCallTracker = ringingCallTracker,
            appForegroundStateService = appForegroundStateService,
            isAudioCall = isAudioCall,
            roomIsDm = roomIsDm,
        ),
        onClose: () -> Unit = {},
        onRequestCameraPermission: () -> Unit = {},
        onRequestScreenCapture: () -> Unit = {},
    ) = NativeCallPresenter(
        navigator = object : NativeCallNavigator {
            override fun close() = onClose()
            override fun requestCameraPermission() = onRequestCameraPermission()
            override fun requestScreenCapture() = onRequestScreenCapture()
        },
        controller = controller,
    )
}
