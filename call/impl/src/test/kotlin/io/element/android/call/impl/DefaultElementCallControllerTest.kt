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
import io.element.android.call.api.ElementCallEndReason
import io.element.android.call.api.ElementCallFailure
import io.element.android.call.api.ElementCallHandRequestFailure
import io.element.android.call.api.ElementCallInterruption
import io.element.android.call.api.ElementCallOptions
import io.element.android.call.api.ElementCallRoomContext
import io.element.android.call.api.ElementCallWindowRect
import io.element.android.call.api.rtc.MatrixRtcAudioLevel
import io.element.android.call.api.rtc.MatrixRtcCallEnd
import io.element.android.call.api.rtc.MatrixRtcCallEvent
import io.element.android.call.api.rtc.MatrixRtcCallIntent
import io.element.android.call.api.rtc.MatrixRtcCallKind
import io.element.android.call.api.rtc.MatrixRtcCallStatus
import io.element.android.call.api.rtc.MatrixRtcConnectionLossReason
import io.element.android.call.api.rtc.MatrixRtcConnectionState
import io.element.android.call.api.rtc.MatrixRtcDetailWindow
import io.element.android.call.api.rtc.MatrixRtcFrameEncryptionDiagnostic
import io.element.android.call.api.rtc.MatrixRtcFrameEncryptionState
import io.element.android.call.api.rtc.MatrixRtcGroupNotify
import io.element.android.call.api.rtc.MatrixRtcInterruptions
import io.element.android.call.api.rtc.MatrixRtcJoinException
import io.element.android.call.api.rtc.MatrixRtcLocalState
import io.element.android.call.api.rtc.MatrixRtcMediaPhase
import io.element.android.call.api.rtc.MatrixRtcMembershipFormat
import io.element.android.call.api.rtc.MatrixRtcReceiveStats
import io.element.android.call.api.rtc.MatrixRtcRing
import io.element.android.call.api.rtc.MatrixRtcRingPhase
import io.element.android.call.api.rtc.MatrixRtcScreenCaptureToken
import io.element.android.call.api.rtc.MatrixRtcSignallingHealth
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcStreamRef
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.MatrixRtcTileKind
import io.element.android.call.api.rtc.MatrixRtcUnableToDecrypt
import io.element.android.call.api.rtc.id.UserId
import io.element.android.call.impl.audio.CallSoundEffect
import io.element.android.call.test.A_ROOM_ID
import io.element.android.call.test.FakeElementCallLifecycleListener
import io.element.android.call.test.FakeElementCallRoomContextProvider
import io.element.android.call.test.FakeMatrixRtcCall
import io.element.android.call.test.FakeMatrixRtcClient
import io.element.android.call.test.aRoster
import io.element.android.call.test.aTile
import io.element.android.call.test.aWindowedRoster
import io.element.android.call.test.audio.FakeAudioFocus
import io.element.android.call.test.audio.FakeCallAudioDeviceController
import io.element.android.call.test.audio.aBluetoothHeadset
import io.element.android.call.test.audio.aSpeaker
import io.element.android.call.test.audio.anEarpiece
import io.element.android.call.tests.testutils.WarmUpRule
import io.element.android.call.tests.testutils.consumeItemsUntilPredicate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)

        controller.startCall(ElementCallData(roomId = A_ROOM_ID, isAudioCall = false, isStartingCall = true))
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(controller.state.value?.callData?.isAudioCall).isTrue()
        assertThat(rtcClient.roomCount).isEqualTo(1)
    }

    @Test
    fun `denying the microphone fails the call rather than joining silently`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)

        controller.state.filterNotNull().test {
            awaitItem()
            controller.setMicrophonePermissionGranted(false)

            val states = consumeItemsUntilPredicate { it.connection is ElementCallConnection.Failed }
            assertThat(states.last().connection).isInstanceOf(ElementCallConnection.Failed::class.java)
            assertThat(rtcClient.roomCount).isEqualTo(0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `granting the microphone joins and connects media`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)

        controller.state.filterNotNull().test {
            awaitItem()
            controller.setMicrophonePermissionGranted(true)

            val states = consumeItemsUntilPredicate { it.connection == ElementCallConnection.Connected }
            assertThat(states.last().isMicrophonePermissionGranted).isTrue()
            assertThat(rtcClient.roomCount).isEqualTo(1)
            assertThat(rtcClient.lastCall?.connectMediaCount).isEqualTo(1)
            assertThat(rtcClient.lastCall?.lastMediaSession?.publishMicrophoneCount).isEqualTo(1)
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
        val rtcClient = FakeMatrixRtcClient()
        val audioDevices = FakeCallAudioDeviceController()
        val platform = FakeElementCallPlatform()
        var startedServiceBeforeCapture: Boolean? = null
        val audioFocus = FakeAudioFocus(
            // Audio focus is taken right before capture, so this is the moment to look.
            requestAudioFocusResult = { startedServiceBeforeCapture = platform.startForegroundServiceCount == 1 },
            releaseAudioFocusResult = {},
        )
        val controller = createController(
            rtcClient = rtcClient,
            audioDeviceController = audioDevices,
            platform = platform,
            audioFocus = audioFocus,
        )

        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Connected)
        assertThat(startedServiceBeforeCapture).isTrue()
        assertThat(rtcClient.lastCall?.lastMediaSession?.publishMicrophoneCount).isEqualTo(1)
        assertThat(audioDevices.startCount).isEqualTo(1)
        assertThat(audioDevices.stopCount).isEqualTo(0)
        assertThat(platform.stopForegroundServiceCount).isEqualTo(0)
        assertThat(rtcClient.lastCall?.leaveCount).isEqualTo(0)
    }

    /** In every format: a branch that skipped the count would report an empty call while media worked. */
    @Test
    fun `a call reports the core's member count`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)

        controller.state.filterNotNull().test {
            awaitItem()
            controller.setMicrophonePermissionGranted(true)
            consumeItemsUntilPredicate { it.connection == ElementCallConnection.Connected }

            // memberCount, not members.size. Both say who is in the slot, but the core answers them
            // differently: memberCount is a question we ask and it counts on the spot, while members
            // arrives on a subscription the core has to wake - and it does not wake it for the
            // Element Call compat path, so members can sit empty for an entire working call.
            // Pushed rather than read at zero, which is the initial value and would pass unsubscribed.
            rtcClient.lastCall?.memberCount?.value = 1

            val states = consumeItemsUntilPredicate { it.memberCount == 1 }
            assertThat(states.last().memberCount).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Temporary, with the pin it tests: until the sticky modes have been tried against other clients the
     * option is read but not obeyed. Restore per-mode tests when the pin goes (see `docs/FEEDBACK.md`, "SDK backend").
     */
    @Test
    fun `a call opens its room in the room-state membership format whatever the option says`() = runTest {
        MatrixRtcMembershipFormat.entries.forEach { preferred ->
            val rtcClient = FakeMatrixRtcClient()
            val controller = createController(rtcClient = rtcClient, membershipFormat = preferred)

            controller.state.filterNotNull().test {
                awaitItem()
                controller.setMicrophonePermissionGranted(true)

                consumeItemsUntilPredicate { it.connection == ElementCallConnection.Connected }
                // Passed to the room rather than merely stored: the format also fixes the member id, the SFU
                // identity and the token endpoint, so a call joined with the wrong one connects and
                // stays silent rather than failing.
                assertThat(rtcClient.lastMembershipFormat).isEqualTo(MatrixRtcMembershipFormat.ROOM_STATE)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun `a failed join fails the call, and dismissing it shuts the room down`() = runTest {
        val rtcClient = FakeMatrixRtcClient(joinResult = { Result.failure(IllegalStateException("Homeserver offers no LiveKit transport")) })
        val controller = createController(rtcClient = rtcClient)

        controller.state.filterNotNull().test {
            awaitItem()
            controller.setMicrophonePermissionGranted(true)

            val states = consumeItemsUntilPredicate { it.connection is ElementCallConnection.Failed }
            val failure = (states.last().connection as ElementCallConnection.Failed).failure as ElementCallFailure.Other
            assertThat(failure.message).contains("LiveKit")
            assertThat(rtcClient.lastRoom?.shutdownCount).isEqualTo(0)
            cancelAndIgnoreRemainingEvents()
        }
        controller.hangUp()
        runCurrent()

        assertThat(rtcClient.lastRoom?.shutdownCount).isEqualTo(1)
    }

    @Test
    fun `hanging up disconnects the media, leaves the session and clears the call`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val lifecycleListener = FakeElementCallLifecycleListener()
        val controller = createController(rtcClient = rtcClient, lifecycleListener = lifecycleListener)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        controller.hangUp()
        runCurrent()

        assertThat(rtcClient.lastCall?.lastMediaSession?.disconnectCount).isEqualTo(1)
        assertThat(rtcClient.lastCall?.leaveCount).isEqualTo(1)
        assertThat(rtcClient.lastRoom?.shutdownCount).isEqualTo(1)
        // Null rather than Ended: the screen closes on the call going away, and the bar with it.
        assertThat(controller.state.value).isNull()
        assertThat(lifecycleListener.endedCalls.map { it?.roomId }).containsExactly(A_ROOM_ID)
    }

    @Test
    fun `a call ended by the far end is torn down without leaving`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        rtcClient.lastCall!!.status.value = MatrixRtcCallStatus.Ended(MatrixRtcCallEnd.SlotClosed)
        runCurrent()

        assertThat(controller.state.value).isNull()
        assertThat(rtcClient.lastCall?.lastMediaSession?.disconnectCount).isEqualTo(1)
        assertThat(rtcClient.lastCall?.leaveCount).isEqualTo(0)
    }

    /** 034 R27: the reason stays until the user closes it, and the core has already left. */
    @Test
    fun `a lost connection is shown until dismissed, and is not left again`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val lifecycleListener = FakeElementCallLifecycleListener()
        val controller = createController(rtcClient = rtcClient, lifecycleListener = lifecycleListener)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        rtcClient.lastCall!!.status.value =
            MatrixRtcCallStatus.Ended(MatrixRtcCallEnd.ConnectionClosed(MatrixRtcConnectionLossReason.NEVER_CONNECTED, "SFU went away"))
        runCurrent()
        assertThat(controller.state.value?.connection)
            .isEqualTo(ElementCallConnection.Failed(ElementCallFailure.ConnectionLost(MatrixRtcConnectionLossReason.NEVER_CONNECTED)))

        controller.hangUp()
        runCurrent()
        assertThat(controller.state.value).isNull()
        assertThat(rtcClient.lastCall?.leaveCount).isEqualTo(0)
        assertThat(lifecycleListener.endReasons).containsExactly(ElementCallEndReason.CONNECTION_CLOSED)
    }

    @Test
    fun `a join the homeserver refused says so`() = runTest {
        val rtcClient = FakeMatrixRtcClient(joinResult = { Result.failure(MatrixRtcJoinException.Refused(errcode = "M_FORBIDDEN", reason = "not allowed")) })
        val controller = createController(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Failed(ElementCallFailure.JoinRefused))
    }

    @Test
    fun `a join that timed out says so`() = runTest {
        val rtcClient = FakeMatrixRtcClient(joinResult = { Result.failure(MatrixRtcJoinException.TimedOut()) })
        val controller = createController(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Failed(ElementCallFailure.JoinTimedOut))
    }

    /** Every end but our own is one the core already left on (030 C3): leaving again would be a second leave. */
    @Test
    fun `a DM call the callee declined is torn down without leaving`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val lifecycleListener = FakeElementCallLifecycleListener()
        val controller = createController(rtcClient = rtcClient, roomIsDm = true, lifecycleListener = lifecycleListener)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        rtcClient.lastCall!!.status.value = MatrixRtcCallStatus.Ended(MatrixRtcCallEnd.Declined)
        runCurrent()

        assertThat(controller.state.value).isNull()
        assertThat(rtcClient.lastCall?.leaveCount).isEqualTo(0)
        assertThat(lifecycleListener.endedCalls.map { it?.roomId }).containsExactly(A_ROOM_ID)
    }

    /** The status is followed from the join: a ring that ends the call before media is up still ends it. */
    @Test
    fun `a call that ends before its media connects is torn down`() = runTest {
        val call = FakeMatrixRtcCall(A_ROOM_ID, "m.call#room")
        call.status.value = MatrixRtcCallStatus.Ended(MatrixRtcCallEnd.Declined)
        val rtcClient = FakeMatrixRtcClient(joinResult = { Result.success(call) })
        val controller = createController(rtcClient = rtcClient, roomIsDm = true)

        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(controller.state.value).isNull()
        assertThat(call.leaveCount).isEqualTo(0)
    }

    /** A ringing DM is not connected, however far our own media has got (030 R20): only the core's pickup is. */
    @Test
    fun `a ringing call is not connected until the core says so`() = runTest {
        val call = FakeMatrixRtcCall(A_ROOM_ID, "m.call#room")
        val calling = MatrixRtcCallStatus.Calling(
            callee = UserId("@callee:example.org"),
            ring = MatrixRtcRingPhase.Ringing(sentAtMs = 0L, lifetimeMs = 90_000L),
            media = MatrixRtcMediaPhase.Connected(degraded = false),
        )
        call.status.value = calling
        val platform = FakeElementCallPlatform(elapsedRealtimeMs = 10_000L, currentTimeMs = 1_000_000L)
        val rtcClient = FakeMatrixRtcClient(joinResult = { Result.success(call) })
        val controller = createController(rtcClient = rtcClient, roomIsDm = true, platform = platform)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Calling(calling.callee, isRinging = true))
        assertThat(controller.state.value?.connectedAtElapsedMs).isNull()

        // Picked up three seconds ago, on the core's wall clock.
        call.status.value = MatrixRtcCallStatus.Connected(sinceMs = 997_000L, degraded = false)
        runCurrent()

        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Connected)
        assertThat(controller.state.value?.connectedAtElapsedMs).isEqualTo(7_000L)
    }

    @Test
    fun `a DM call we start shows that it is calling, then rings, then connects at pickup`() = runTest {
        val call = FakeMatrixRtcCall(A_ROOM_ID, "m.call#room")
        val callee = UserId("@callee:example.org")
        val controller = createController(rtcClient = FakeMatrixRtcClient(joinResult = { Result.success(call) }), roomIsDm = true)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        call.status.value = MatrixRtcCallStatus.Calling(callee, MatrixRtcRingPhase.Sending, MatrixRtcMediaPhase.Connecting)
        runCurrent()
        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Calling(callee, isRinging = false))

        call.status.value = MatrixRtcCallStatus.Calling(callee, MatrixRtcRingPhase.Ringing(0L, 90_000L), MatrixRtcMediaPhase.Connecting)
        runCurrent()
        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Calling(callee, isRinging = true))

        call.status.value = MatrixRtcCallStatus.Connected(sinceMs = 0L, degraded = false)
        runCurrent()
        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Connected)
    }

    @Test
    fun `a DM call shows that it is calling while the join is in flight`() = runTest {
        val joinGate = CompletableDeferred<Unit>()
        val rtcClient = FakeMatrixRtcClient(joinGate = joinGate)
        val controller = createController(rtcClient = rtcClient, roomIsDm = true)

        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Calling(callee = null, isRinging = false))
        joinGate.complete(Unit)
    }

    /** 030 R4: the caller is told, and the call has already been left by the core. */
    @Test
    fun `a ring that could not be sent is shown until dismissed, and is not left again`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val lifecycleListener = FakeElementCallLifecycleListener()
        val controller = createController(rtcClient = rtcClient, roomIsDm = true, lifecycleListener = lifecycleListener)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        rtcClient.lastCall!!.status.value = MatrixRtcCallStatus.Ended(MatrixRtcCallEnd.RingNotSent)
        runCurrent()
        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.RingNotSent(callee = null))

        controller.hangUp()
        runCurrent()
        assertThat(controller.state.value).isNull()
        assertThat(rtcClient.lastCall?.leaveCount).isEqualTo(0)
        assertThat(lifecycleListener.endReasons).containsExactly(ElementCallEndReason.RING_NOT_SENT)
    }

    @Test
    fun `the host is told why the call ended`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val lifecycleListener = FakeElementCallLifecycleListener()
        val controller = createController(rtcClient = rtcClient, roomIsDm = true, lifecycleListener = lifecycleListener)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        rtcClient.lastCall!!.status.value = MatrixRtcCallStatus.Ended(MatrixRtcCallEnd.OthersLeft)
        runCurrent()

        assertThat(lifecycleListener.endReasons).containsExactly(ElementCallEndReason.OTHERS_LEFT)
    }

    @Test
    fun `a hang-up is reported as ours`() = runTest {
        val lifecycleListener = FakeElementCallLifecycleListener()
        val controller = createController(lifecycleListener = lifecycleListener)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        controller.hangUp()
        runCurrent()

        assertThat(lifecycleListener.endReasons).containsExactly(ElementCallEndReason.LEFT)
    }

    /** 030 R23, R24: the ringback plays through the call's audio before media, so the route is up from the join. */
    @Test
    fun `the route and the sounds are up from the join, before media`() = runTest {
        val joinedCall = FakeMatrixRtcCall(A_ROOM_ID, "m.call#room")
        val audioDeviceController = FakeCallAudioDeviceController()
        val platform = FakeElementCallPlatform()
        var routedBeforeMedia: Boolean? = null
        val rtcClient = FakeMatrixRtcClient(joinResult = { Result.success(joinedCall) })
        val controller = createController(rtcClient = rtcClient, audioDeviceController = audioDeviceController, platform = platform, roomIsDm = true)
        joinedCall.onConnectMedia = { routedBeforeMedia = audioDeviceController.startCount == 1 && platform.soundEffectRenderers.size == 1 }

        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(routedBeforeMedia).isTrue()
    }

    @Test
    fun `the ringback loops while it rings and stops at pickup`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val platform = FakeElementCallPlatform()
        val controller = createController(rtcClient = rtcClient, platform = platform, roomIsDm = true)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val call = rtcClient.lastCall!!
        val renderer = platform.soundEffectRenderers.single()
        val callee = UserId("@callee:example.org")

        call.status.value = MatrixRtcCallStatus.Calling(callee, MatrixRtcRingPhase.Sending, MatrixRtcMediaPhase.Connecting)
        runCurrent()
        // Not before the ring has gone out (030 R4).
        assertThat(renderer.looping).isNull()

        call.status.value = MatrixRtcCallStatus.Calling(callee, MatrixRtcRingPhase.Ringing(0L, 90_000L), MatrixRtcMediaPhase.Connecting)
        runCurrent()
        assertThat(renderer.looping).isEqualTo(CallSoundEffect.RINGBACK)

        call.status.value = MatrixRtcCallStatus.Connected(sinceMs = 0L, degraded = false)
        runCurrent()
        assertThat(renderer.looping).isNull()
        assertThat(controller.state.value).isNotNull()
    }

    /** 030 R25: the screen closes at once, and the call's audio goes only once the sound has played. */
    @Test
    fun `a decline closes the screen, then plays its sound to the end before the audio is released`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val platform = FakeElementCallPlatform()
        val audioDeviceController = FakeCallAudioDeviceController()
        val lifecycleListener = FakeElementCallLifecycleListener()
        val controller = createController(
            rtcClient = rtcClient,
            platform = platform,
            audioDeviceController = audioDeviceController,
            lifecycleListener = lifecycleListener,
            roomIsDm = true,
        )
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val renderer = platform.soundEffectRenderers.single()
        renderer.endSoundGate = CompletableDeferred()

        rtcClient.lastCall!!.status.value = MatrixRtcCallStatus.Ended(MatrixRtcCallEnd.Declined)
        runCurrent()

        assertThat(controller.state.value).isNull()
        assertThat(renderer.playedToEnd).containsExactly(CallSoundEffect.DECLINED)
        assertThat(renderer.releaseCount).isEqualTo(0)
        assertThat(audioDeviceController.stopCount).isEqualTo(0)
        assertThat(platform.stopForegroundServiceCount).isEqualTo(0)

        renderer.endSoundGate!!.complete(Unit)
        runCurrent()

        assertThat(renderer.releaseCount).isEqualTo(1)
        assertThat(audioDeviceController.stopCount).isEqualTo(1)
        assertThat(platform.stopForegroundServiceCount).isEqualTo(1)
        assertThat(lifecycleListener.endReasons).containsExactly(ElementCallEndReason.DECLINED)
    }

    @Test
    fun `a ring that runs out plays the timeout sound, a hang-up plays none`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val platform = FakeElementCallPlatform()
        val controller = createController(rtcClient = rtcClient, platform = platform, roomIsDm = true)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        rtcClient.lastCall!!.status.value = MatrixRtcCallStatus.Ended(MatrixRtcCallEnd.Unanswered)
        runCurrent()
        assertThat(platform.soundEffectRenderers.single().playedToEnd).containsExactly(CallSoundEffect.TIMEOUT)

        controller.startCall(ElementCallData(roomId = A_ROOM_ID, isAudioCall = true, isStartingCall = true))
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        controller.hangUp()
        runCurrent()
        assertThat(platform.soundEffectRenderers.last().playedToEnd).isEmpty()
    }

    /** The end sound must not hand back the route, the focus and the service a new call has just taken. */
    @Test
    fun `a call started while the end sound plays keeps its audio`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val platform = FakeElementCallPlatform()
        val audioDeviceController = FakeCallAudioDeviceController()
        val controller = createController(rtcClient = rtcClient, platform = platform, audioDeviceController = audioDeviceController, roomIsDm = true)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val renderer = platform.soundEffectRenderers.single()
        renderer.endSoundGate = CompletableDeferred()
        rtcClient.lastCall!!.status.value = MatrixRtcCallStatus.Ended(MatrixRtcCallEnd.Declined)
        runCurrent()

        controller.startCall(ElementCallData(roomId = A_ROOM_ID, isAudioCall = true, isStartingCall = true))
        runCurrent()
        renderer.endSoundGate!!.complete(Unit)
        runCurrent()

        assertThat(renderer.releaseCount).isEqualTo(1)
        assertThat(audioDeviceController.stopCount).isEqualTo(0)
        assertThat(platform.stopForegroundServiceCount).isEqualTo(0)
        assertThat(controller.state.value).isNotNull()
    }

    @Test
    fun `a join and a leave play their sounds, a screen share plays nothing`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val platform = FakeElementCallPlatform()
        val controller = createController(rtcClient = rtcClient, platform = platform)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val session = rtcClient.lastCall?.lastMediaSession!!

        session.emit(MatrixRtcCallEvent.ParticipantJoined(A_REMOTE_MEMBER_ID, UserId("@bob:example.org"), participantCount = 2))
        session.emit(MatrixRtcCallEvent.StreamStarted(A_REMOTE_MEMBER_ID, MatrixRtcStreamKind.SCREEN_SHARE))
        session.emit(MatrixRtcCallEvent.StreamStopped(A_REMOTE_MEMBER_ID, MatrixRtcStreamKind.SCREEN_SHARE))
        platform.elapsedRealtimeMs = 200
        session.emit(MatrixRtcCallEvent.ParticipantLeft(A_REMOTE_MEMBER_ID, participantCount = 1))
        runCurrent()

        assertThat(platform.soundEffectRenderers.single().played).containsExactly(CallSoundEffect.JOIN, CallSoundEffect.LEAVE).inOrder()
    }

    @Test
    fun `the host's cap silences joins above it`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val platform = FakeElementCallPlatform()
        val controller = createController(rtcClient = rtcClient, platform = platform, participantCapForJoinLeaveSounds = 2)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        rtcClient.lastCall?.lastMediaSession!!.emit(
            MatrixRtcCallEvent.ParticipantJoined(ANOTHER_REMOTE_MEMBER_ID, UserId("@carol:example.org"), participantCount = 3),
        )
        runCurrent()

        assertThat(platform.soundEffectRenderers.single().played).isEmpty()
    }

    @Test
    fun `hanging up releases the sounds`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val platform = FakeElementCallPlatform()
        val controller = createController(rtcClient = rtcClient, platform = platform)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        controller.hangUp()
        runCurrent()

        assertThat(platform.soundEffectRenderers.single().releaseCount).isEqualTo(1)
    }

    @Test
    fun `nothing plays once the far end ended the call`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val platform = FakeElementCallPlatform()
        val controller = createController(rtcClient = rtcClient, platform = platform)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val session = rtcClient.lastCall?.lastMediaSession!!

        rtcClient.lastCall!!.status.value = MatrixRtcCallStatus.Ended(MatrixRtcCallEnd.SlotClosed)
        session.emit(MatrixRtcCallEvent.ParticipantLeft(A_REMOTE_MEMBER_ID, participantCount = 1))
        runCurrent()

        val renderer = platform.soundEffectRenderers.single()
        assertThat(renderer.played).isEmpty()
        assertThat(renderer.releaseCount).isEqualTo(1)
    }

    @Test
    fun `muting is forwarded to the call and read back from it`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)

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
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)

        controller.state.filterNotNull().test {
            awaitItem()
            controller.setMicrophonePermissionGranted(true)
            consumeItemsUntilPredicate { it.connection == ElementCallConnection.Connected }

            val call = rtcClient.lastCall?.lastMediaSession!!
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
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)

        controller.state.filterNotNull().test {
            awaitItem()
            controller.setMicrophonePermissionGranted(true)
            consumeItemsUntilPredicate { it.connection == ElementCallConnection.Connected }
            val call = rtcClient.lastCall?.lastMediaSession!!

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
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)

        controller.state.filterNotNull().test {
            awaitItem()
            controller.setMicrophonePermissionGranted(true)
            consumeItemsUntilPredicate { it.connection == ElementCallConnection.Connected }

            val call = rtcClient.lastCall?.lastMediaSession!!
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

    /** 034 R35: a blip shorter than the hold never shows; once up, the list follows the causes at once. */
    @Test
    fun `interruptions show once a cause has lasted the hold, then follow the causes`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val session = rtcClient.lastCall!!.lastMediaSession!!

        session.interruptions.value = MatrixRtcInterruptions.NONE.copy(ownMedia = MatrixRtcConnectionState.RECONNECTING)
        advanceTimeBy(DefaultElementCallController.INTERRUPTION_HOLD - 1.milliseconds)
        session.interruptions.value = MatrixRtcInterruptions.NONE
        runCurrent()
        advanceTimeBy(DefaultElementCallController.INTERRUPTION_HOLD)
        assertThat(controller.state.value?.interruptions).isEmpty()

        session.interruptions.value = MatrixRtcInterruptions.NONE.copy(ownMedia = MatrixRtcConnectionState.RECONNECTING)
        advanceTimeBy(DefaultElementCallController.INTERRUPTION_HOLD + 1.milliseconds)
        assertThat(controller.state.value?.interruptions).containsExactly(ElementCallInterruption.OwnMedia)

        session.interruptions.value = MatrixRtcInterruptions.NONE.copy(
            ownMedia = MatrixRtcConnectionState.RECONNECTING,
            membersUnreachable = listOf(A_REMOTE_MEMBER_ID),
        )
        runCurrent()
        assertThat(controller.state.value?.interruptions).containsExactly(ElementCallInterruption.OwnMedia, ElementCallInterruption.OthersMedia).inOrder()

        session.interruptions.value = MatrixRtcInterruptions.NONE
        runCurrent()
        assertThat(controller.state.value?.interruptions).isEmpty()
    }

    /** 034 Q8: counted from the core's report, per spell; a spell that ends clears it. */
    @Test
    fun `a member unable to decrypt for long enough is reported missing keys, until they decrypt again`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val session = rtcClient.lastCall!!.lastMediaSession!!

        session.tiles.value = aRoster(aTile(A_REMOTE_MEMBER_ID, unableToDecrypt = MatrixRtcUnableToDecrypt.MISSING_KEY))
        advanceTimeBy(DefaultElementCallController.KEYS_MISSING_AFTER - 1.milliseconds)
        assertThat(controller.state.value?.membersMissingKeys).isEmpty()
        advanceTimeBy(2.milliseconds)
        assertThat(controller.state.value?.membersMissingKeys).containsExactly(A_REMOTE_MEMBER_ID)

        session.tiles.value = aRoster(aTile(A_REMOTE_MEMBER_ID))
        runCurrent()
        assertThat(controller.state.value?.membersMissingKeys).isEmpty()
    }

    /** 034 C4: the worse of the client's health and the record's copy, so a hop between the two never reads reachable. */
    @Test
    fun `an unreachable homeserver is the server entry, from either source`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val session = rtcClient.lastCall!!.lastMediaSession!!

        session.interruptions.value = MatrixRtcInterruptions.NONE.copy(signalling = MatrixRtcSignallingHealth.Unreachable(sinceMs = 3))
        advanceTimeBy(DefaultElementCallController.INTERRUPTION_HOLD + 1.milliseconds)

        assertThat(controller.state.value?.signalling).isEqualTo(MatrixRtcSignallingHealth.Unreachable(sinceMs = 3))
        assertThat(controller.state.value?.interruptions).containsExactly(ElementCallInterruption.OwnServer(isMembershipLapsed = false))
    }

    @Test
    fun `the test tone is forwarded to the call`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)

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
        val rtcClient = FakeMatrixRtcClient()
        val audioDevices = FakeCallAudioDeviceController(initialDevices = listOf(anEarpiece(), aSpeaker()))
        val controller = createController(rtcClient = rtcClient, audioDeviceController = audioDevices)

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
        val rtcClient = FakeMatrixRtcClient()
        val audioDevices = FakeCallAudioDeviceController(initialDevices = listOf(anEarpiece(), aSpeaker()))
        val controller = createController(rtcClient = rtcClient, audioDeviceController = audioDevices, isAudioCall = false)

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
        val rtcClient = FakeMatrixRtcClient()
        val audioDevices = FakeCallAudioDeviceController(initialDevices = listOf(aBluetoothHeadset(), anEarpiece(), aSpeaker()))
        val controller = createController(rtcClient = rtcClient, audioDeviceController = audioDevices, isAudioCall = false)

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
        val rtcClient = FakeMatrixRtcClient()
        val audioDevices = FakeCallAudioDeviceController(initialDevices = listOf(anEarpiece(), aSpeaker()))
        val controller = createController(rtcClient = rtcClient, audioDeviceController = audioDevices)

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
        val rtcClient = FakeMatrixRtcClient()
        val audioDevices = FakeCallAudioDeviceController()
        val platform = FakeElementCallPlatform()
        var releaseCount = 0
        val controller = createController(
            rtcClient = rtcClient,
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
        val rtcClient = FakeMatrixRtcClient()
        val audioDevices = FakeCallAudioDeviceController()
        val platform = FakeElementCallPlatform()
        val controller = createController(rtcClient = rtcClient, audioDeviceController = audioDevices, platform = platform)
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
        assertThat(rtcClient.lastCall?.leaveCount).isEqualTo(0)

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
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient, isAudioCall = false)

        // The host asks for the camera up front on a video call, so it is granted before media
        // connects. This is the ordering where the controller has to act on it itself.
        controller.setCameraPermissionGranted(true)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Connected)
        assertThat(rtcClient.lastCall?.lastMediaSession?.cameraEnabledCalls).contains(true)
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
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)

        // Nothing to mute yet: the permission has not been answered, so there is no session, let
        // alone a call. The tap has nowhere to go but the snapshot.
        controller.setMicrophoneMuted(true)
        runCurrent()
        assertThat(controller.state.value?.isMicrophoneMuted).isTrue()

        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Connected)
        assertThat(rtcClient.lastCall?.lastMediaSession?.publishMicrophoneCalls).containsExactly(true)
        // And it is still muted once connected: the observers start after the publication, so what
        // they report back is the state it was published in rather than a default that undoes it.
        assertThat(controller.state.value?.isMicrophoneMuted).isTrue()
    }

    @Test
    fun `a microphone nobody touched is published unmuted`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)

        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(rtcClient.lastCall?.lastMediaSession?.publishMicrophoneCalls).containsExactly(false)
        assertThat(controller.state.value?.isMicrophoneMuted).isFalse()
    }

    @Test
    fun `a mute taken back while the call connects publishes the microphone unmuted`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)

        controller.setMicrophoneMuted(true)
        controller.setMicrophoneMuted(false)
        runCurrent()

        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(rtcClient.lastCall?.lastMediaSession?.publishMicrophoneCalls).containsExactly(false)
        assertThat(controller.state.value?.isMicrophoneMuted).isFalse()
    }

    @Test
    fun `turning the camera on while the call connects starts it once connected`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient, isAudioCall = true)

        // The permission answered first, which is what makes the button live: without it the tap
        // asks for the permission instead, and that answer is not an ask for the camera.
        controller.setCameraPermissionGranted(true)
        controller.setCameraEnabled(true)
        runCurrent()
        assertThat(controller.state.value?.isCameraEnabled).isTrue()

        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(rtcClient.lastCall?.lastMediaSession?.cameraEnabledCalls).containsExactly(true)
        assertThat(controller.state.value?.isCameraEnabled).isTrue()
    }

    /**
     * The mirror image, and the reason the ask is kept beside the snapshot rather than read off it:
     * a video call starts the camera by default, so "off" has to be distinguishable from "untouched".
     */
    @Test
    fun `turning the camera off while a video call connects leaves it off`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient, isAudioCall = false)

        controller.setCameraPermissionGranted(true)
        controller.setCameraEnabled(false)
        runCurrent()

        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Connected)
        assertThat(rtcClient.lastCall?.lastMediaSession?.cameraEnabledCalls).isEmpty()
        assertThat(controller.state.value?.isCameraEnabled).isFalse()
    }

    @Test
    fun `an audio call leaves the camera off`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient, isAudioCall = true)

        controller.setCameraPermissionGranted(true)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        // Granting the camera is not the same as wanting it on: an audio call that answered the
        // permission dialog must still start with the camera off.
        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Connected)
        assertThat(rtcClient.lastCall?.lastMediaSession?.isCameraEnabled?.value).isFalse()
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
        val rtcClient = FakeMatrixRtcClient()
        val lifecycleListener = FakeElementCallLifecycleListener()
        val controller = createController(rtcClient = rtcClient, lifecycleListener = lifecycleListener)

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
     * `MatrixRtcMediaSession.videoFrames` is cold and documents "collect it once per member; two collectors
     * means two streams". The call UI legitimately draws a member twice - spotlight and strip - so
     * the controller shares the flow. Getting this wrong is not a rendering glitch: two handles on
     * one track, with the second closing under the first, crashed the core inside
     * `VideoSinkWrapper::on_frame` a few seconds after video started.
     */
    @Test
    fun `two collectors on one member open a single video stream`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val call = rtcClient.lastCall?.lastMediaSession!!

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
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val call = rtcClient.lastCall?.lastMediaSession!!

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
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)
        val composed = setOf(MatrixRtcTileId(A_REMOTE_MEMBER_ID, MatrixRtcTileKind.PERSON))
        controller.setComposedTiles(composed)

        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val call = rtcClient.lastCall?.lastMediaSession!!
        assertThat(call.composedTiles).containsExactly(composed)

        val more = composed + MatrixRtcTileId(ANOTHER_REMOTE_MEMBER_ID, MatrixRtcTileKind.SCREEN_SHARE)
        controller.setComposedTiles(more)
        assertThat(call.composedTiles).containsExactly(composed, more).inOrder()
    }

    /**
     * The roster reaches the snapshot whole: the order with every reference, and the records the
     * window let through. A tile outside the window is a reference the screen can still place, not
     * a tile that vanished.
     */
    @Test
    fun `the roster reaches the snapshot whole, references included`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val call = rtcClient.lastCall?.lastMediaSession!!

        call.tiles.value = aWindowedRoster(
            aTile(A_REMOTE_MEMBER_ID, MatrixRtcTileKind.SCREEN_SHARE),
            aTile(A_REMOTE_MEMBER_ID),
            aTile(ANOTHER_REMOTE_MEMBER_ID),
            detailFor = setOf(
                MatrixRtcTileId(A_REMOTE_MEMBER_ID, MatrixRtcTileKind.SCREEN_SHARE),
                MatrixRtcTileId(A_REMOTE_MEMBER_ID, MatrixRtcTileKind.PERSON),
            ),
        )
        runCurrent()

        val roster = controller.state.value?.roster!!
        assertThat(roster.order.map { it.id.kind })
            .containsExactly(MatrixRtcTileKind.SCREEN_SHARE, MatrixRtcTileKind.PERSON, MatrixRtcTileKind.PERSON)
            .inOrder()
        assertThat(roster.order.map { it.isHero }).containsExactly(true, false, false).inOrder()
        assertThat(roster.detail.keys.map { it.memberId }).containsExactly(A_REMOTE_MEMBER_ID, A_REMOTE_MEMBER_ID)
        assertThat(roster.ranked.map { it.id.kind }).containsExactly(MatrixRtcTileKind.SCREEN_SHARE, MatrixRtcTileKind.PERSON).inOrder()
    }

    /** Never ourselves: the core never ranks our own tile, so alone the order is empty. */
    @Test
    fun `the order never holds us, and is empty when we are alone`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val call = rtcClient.lastCall?.lastMediaSession!!

        call.localState.value = MatrixRtcLocalState(tile = aTile(A_LOCAL_MEMBER_ID, hasVideo = true), isScreenSharing = false)
        runCurrent()
        assertThat(controller.state.value?.roster?.order).isEmpty()
        assertThat(controller.state.value?.ownTile?.id?.memberId).isEqualTo(A_LOCAL_MEMBER_ID)
    }

    /**
     * The layout declares its window before media is up, exactly as it declares the composed set,
     * so the window has to survive until the call exists and reach it - and not outlive the call.
     */
    @Test
    fun `the detail window reaches the call, including one that connects later, and not the next call`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)
        val window = MatrixRtcDetailWindow(ranks = 0 until 6, also = setOf(MatrixRtcTileId(A_REMOTE_MEMBER_ID, MatrixRtcTileKind.SCREEN_SHARE)))
        controller.setDetailWindow(window)

        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val call = rtcClient.lastCall?.lastMediaSession!!
        assertThat(call.detailWindows).containsExactly(window)

        val scrolled = MatrixRtcDetailWindow(ranks = 4 until 12)
        controller.setDetailWindow(scrolled)
        assertThat(call.detailWindows).containsExactly(window, scrolled).inOrder()

        controller.hangUp()
        runCurrent()
        controller.startCall(ElementCallData(roomId = A_ROOM_ID, isAudioCall = true, isStartingCall = true))
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val nextCall = rtcClient.lastCall?.lastMediaSession!!
        assertThat(nextCall).isNotSameInstanceAs(call)
        assertThat(nextCall.detailWindows).isEmpty()
        // The composed set is replayed whatever it holds; what matters is that the last call's is not.
        assertThat(nextCall.composedTiles).containsExactly(emptySet<MatrixRtcTileId>())
    }

    /** The layout sets it once, above every call: it reaches a call that connects later, and the next one. */
    @Test
    fun `the ranking threshold reaches the call, including one that connects later, and the next call`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)
        controller.setRankingThreshold(4)

        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val call = rtcClient.lastCall?.lastMediaSession!!
        assertThat(call.rankingThresholds).containsExactly(4)

        controller.hangUp()
        runCurrent()
        controller.startCall(ElementCallData(roomId = A_ROOM_ID, isAudioCall = true, isStartingCall = true))
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val nextCall = rtcClient.lastCall?.lastMediaSession!!
        assertThat(nextCall).isNotSameInstanceAs(call)
        assertThat(nextCall.rankingThresholds).containsExactly(4)
    }

    /** A device with one camera says so from the start, so our tile never offers a switch (019 R24). */
    @Test
    fun `the snapshot says whether there is a camera to switch to`() = runTest {
        val single = createController(platform = FakeElementCallPlatform(isCameraSwitchAvailable = false))
        assertThat(single.state.value?.isCameraSwitchAvailable).isFalse()

        val both = createController(platform = FakeElementCallPlatform(isCameraSwitchAvailable = true))
        assertThat(both.state.value?.isCameraSwitchAvailable).isTrue()
    }

    /** At or below the threshold the order is join order, heroes first, whoever speaks (019 R7). */
    @Test
    fun `at or below the ranking threshold the order is join order`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)
        controller.setRankingThreshold(2)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val call = rtcClient.lastCall?.lastMediaSession!!

        call.pushRoster(aRoster(aTile(A_REMOTE_MEMBER_ID)))
        call.pushRoster(aRoster(aTile(ANOTHER_REMOTE_MEMBER_ID, isSpeaking = true), aTile(A_REMOTE_MEMBER_ID)))
        runCurrent()
        assertThat(controller.state.value?.roster?.order?.map { it.id.memberId })
            .containsExactly(A_REMOTE_MEMBER_ID, ANOTHER_REMOTE_MEMBER_ID)
            .inOrder()

        call.pushRoster(
            aRoster(
                aTile(ANOTHER_REMOTE_MEMBER_ID, isSpeaking = true),
                aTile(A_REMOTE_MEMBER_ID),
                aTile(ANOTHER_REMOTE_MEMBER_ID, MatrixRtcTileKind.SCREEN_SHARE),
            )
        )
        runCurrent()
        // Three tiles is above the threshold: the core's ranking, as given.
        assertThat(controller.state.value?.roster?.order?.map { it.id.memberId })
            .containsExactly(ANOTHER_REMOTE_MEMBER_ID, A_REMOTE_MEMBER_ID, ANOTHER_REMOTE_MEMBER_ID)
            .inOrder()
    }

    /**
     * The only way to reach this is having just tapped the camera button, so making the user tap it
     * again after saying yes would be a strange reward for granting it.
     */
    @Test
    fun `granting the camera permission turns the camera on`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        controller.setCameraPermissionGranted(true)
        runCurrent()

        assertThat(controller.state.value?.isCameraPermissionGranted).isTrue()
        assertThat(controller.state.value?.isCameraEnabled).isTrue()
        assertThat(rtcClient.lastCall?.lastMediaSession?.cameraEnabledCalls).containsExactly(true)
    }

    @Test
    fun `turning the camera off once it is on stops it`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        controller.setCameraPermissionGranted(true)
        runCurrent()

        controller.setCameraEnabled(false)
        runCurrent()

        assertThat(controller.state.value?.isCameraEnabled).isFalse()
        assertThat(rtcClient.lastCall?.lastMediaSession?.cameraEnabledCalls).containsExactly(true, false).inOrder()
    }

    /**
     * The camera side is asynchronous - it has to close one device and open another - so the state
     * has to come back from the call rather than being assumed at the tap.
     */
    @Test
    fun `switching the camera follows what the call reports rather than the request`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        // Front facing before the camera has ever been opened, which is the side it starts on.
        assertThat(controller.state.value?.isFrontCamera).isTrue()

        controller.switchCamera()
        runCurrent()

        assertThat(controller.state.value?.isFrontCamera).isFalse()
        assertThat(rtcClient.lastCall?.lastMediaSession?.switchCameraCount).isEqualTo(1)
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
        val rtcClient = FakeMatrixRtcClient()
        val audioDeviceController = FakeCallAudioDeviceController()
        val lifecycleListener = FakeElementCallLifecycleListener()
        val controller = createController(
            rtcClient = rtcClient,
            audioDeviceController = audioDeviceController,
            lifecycleListener = lifecycleListener,
        )
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val call = rtcClient.lastCall?.lastMediaSession!!
        call.tiles.value = aRoster(aTile(A_REMOTE_MEMBER_ID, hasVideo = false))
        runCurrent()

        // Maximized, audio only, foreground: the one case it is for.
        assertThat(audioDeviceController.proximityBlankingAllowed).isTrue()

        // Someone turns a camera on - now there is something to look at.
        call.tiles.value = aRoster(aTile(A_REMOTE_MEMBER_ID, hasVideo = true))
        runCurrent()
        assertThat(audioDeviceController.proximityBlankingAllowed).isFalse()

        call.tiles.value = aRoster(aTile(A_REMOTE_MEMBER_ID, hasVideo = false))
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
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val call = rtcClient.lastCall?.lastMediaSession!!

        // Audio only: the bar.
        call.tiles.value = aRoster(aTile(A_REMOTE_MEMBER_ID, hasVideo = false))
        runCurrent()
        assertThat(controller.state.value?.hasVideo).isFalse()

        // Someone turns a camera on: the tile.
        call.tiles.value = aRoster(aTile(A_REMOTE_MEMBER_ID, hasVideo = true))
        runCurrent()
        assertThat(controller.state.value?.hasVideo).isTrue()

        // A screen share counts too, even with every camera off.
        call.tiles.value = aRoster(aTile(A_REMOTE_MEMBER_ID, MatrixRtcTileKind.SCREEN_SHARE), aTile(A_REMOTE_MEMBER_ID, hasVideo = true))
        runCurrent()
        assertThat(controller.state.value?.hasVideo).isTrue()

        // And back off again.
        call.tiles.value = aRoster(aTile(A_REMOTE_MEMBER_ID, hasVideo = false))
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
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)

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
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)
        controller.setInPictureInPicture(true)
        controller.setMaximized(false)
        runCurrent()

        controller.setInPictureInPicture(false)
        runCurrent()

        assertThat(controller.isInPictureInPicture.value).isFalse()
        assertThat(controller.state.value?.isMaximized).isTrue()
    }

    /**
     * The window keeps the shape it was entered with: in it nothing draws the tile, and the screen
     * going away reports null, which would let the next params update reset the window's shape.
     */
    @Test
    fun `the picture-in-picture source is frozen while in picture-in-picture`() = runTest {
        val controller = createController(rtcClient = FakeMatrixRtcClient())
        val tile = ElementCallWindowRect(left = 0, top = 100, right = 400, bottom = 400)
        controller.setPictureInPictureSource(tile)

        controller.setInPictureInPicture(true)
        controller.setPictureInPictureSource(null)

        assertThat(controller.pictureInPictureSource.value).isEqualTo(tile)
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
        val rtcClient = FakeMatrixRtcClient()
        // What the call had been asked to do at the moment the service was upgraded. The ordering is
        // only visible from inside that moment: two counters read afterwards would both be non-zero
        // whichever way round it happened.
        var shareCallsWhenProjectingClaimed: Int? = null
        val platform = FakeElementCallPlatform(
            onStartForegroundService = { isProjecting ->
                if (isProjecting) {
                    shareCallsWhenProjectingClaimed = rtcClient.lastCall?.lastMediaSession?.screenShareCalls?.size
                }
            },
        )
        val controller = createController(rtcClient = rtcClient, platform = platform, isScreenSharingEnabled = true)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val call = rtcClient.lastCall?.lastMediaSession!!
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
        val rtcClient = FakeMatrixRtcClient()
        val platform = FakeElementCallPlatform()
        val controller = createController(rtcClient = rtcClient, platform = platform)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val call = rtcClient.lastCall?.lastMediaSession!!
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
        val rtcClient = FakeMatrixRtcClient()
        val platform = FakeElementCallPlatform()
        val controller = createController(rtcClient = rtcClient, platform = platform, isScreenSharingEnabled = true)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val call = rtcClient.lastCall?.lastMediaSession!!
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
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient, isScreenSharingEnabled = true)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val call = rtcClient.lastCall?.lastMediaSession!!
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
    fun `starting a call in a DM rings the far end`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient, roomIsDm = true, isAudioCall = false)

        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        // The intent rides along, so the callee is told what they are being invited to.
        assertThat(rtcClient.lastRoom?.lastKind).isEqualTo(MatrixRtcCallKind.Direct(MatrixRtcRing(MatrixRtcCallIntent.VIDEO)))
    }

    /** Answering never rings back (030 R33): a caller who just gave up would otherwise be called. */
    @Test
    fun `answering in a DM joins without a ring`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient, roomIsDm = true, isStartingCall = false)

        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(rtcClient.lastRoom?.lastKind).isEqualTo(MatrixRtcCallKind.Direct(ring = null))
    }

    @Test
    fun `the ring lasts the host's lifetime`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient, roomIsDm = true, ringLifetime = 45.seconds)

        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat((rtcClient.lastRoom?.lastKind as MatrixRtcCallKind.Direct).ring?.lifetimeMs).isEqualTo(45_000uL)
    }

    /**
     * The other half of the rule, and the one with a cost attached: ringing a group room summons
     * everyone in it, so anything that is not a DM only gets a silent notification.
     */
    @Test
    fun `starting a call in a room notifies without ringing`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient, roomIsDm = false, isAudioCall = true)

        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(rtcClient.lastRoom?.lastKind).isEqualTo(MatrixRtcCallKind.Group(MatrixRtcGroupNotify(MatrixRtcCallIntent.AUDIO)))
    }

    @Test
    fun `joining a call in a room notifies nobody`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient, roomIsDm = false, isStartingCall = false)

        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(rtcClient.lastRoom?.lastKind).isEqualTo(MatrixRtcCallKind.Group(notify = null))
    }

    /**
     * A room the host cannot describe in time is treated as a group: an unwanted ring wakes people
     * up, a missing one only makes the call quieter than it should have been. And the join is not
     * held hostage to the answer - the call still connects.
     */
    @Test
    fun `a room that cannot be read in time notifies rather than rings, and the call still joins`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient, roomIsDm = null, isAudioCall = false)

        controller.setMicrophonePermissionGranted(true)
        advanceTimeBy(DefaultElementCallController.ROOM_CONTEXT_TIMEOUT_MS + 1)
        runCurrent()

        assertThat(rtcClient.lastRoom?.lastKind).isInstanceOf(MatrixRtcCallKind.Group::class.java)
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
    @Test
    fun `a hand going up plays its sound, coming down plays nothing, and one to one stays silent`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val platform = FakeElementCallPlatform()
        val controller = createController(rtcClient = rtcClient, platform = platform)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val session = rtcClient.lastCall?.lastMediaSession!!
        session.localState.value = MatrixRtcLocalState(tile = aTile(A_LOCAL_MEMBER_ID), isScreenSharing = false)
        session.tiles.value = aRoster(aTile(A_REMOTE_MEMBER_ID), aTile(ANOTHER_REMOTE_MEMBER_ID))
        runCurrent()

        session.emit(MatrixRtcCallEvent.HandRaised(A_REMOTE_MEMBER_ID))
        session.emit(MatrixRtcCallEvent.HandLowered(A_REMOTE_MEMBER_ID))
        runCurrent()
        session.tiles.value = aRoster(aTile(A_REMOTE_MEMBER_ID))
        platform.elapsedRealtimeMs = 10_000
        session.emit(MatrixRtcCallEvent.HandRaised(A_REMOTE_MEMBER_ID))
        runCurrent()

        assertThat(platform.soundEffectRenderers.single().played).containsExactly(CallSoundEffect.RAISE_HAND)
    }

    /** 034 C4: followed from the start of a call, before the join returns. */
    @Test
    fun `signalling health is followed from the start of the call`() = runTest {
        val joinGate = CompletableDeferred<Unit>()
        val rtcClient = FakeMatrixRtcClient(joinGate = joinGate)
        val controller = createController(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        rtcClient.signalling.value = MatrixRtcSignallingHealth.Unreachable(sinceMs = 7)
        runCurrent()
        assertThat(controller.state.value?.signalling).isEqualTo(MatrixRtcSignallingHealth.Unreachable(sinceMs = 7))

        joinGate.complete(Unit)
        rtcClient.signalling.value = MatrixRtcSignallingHealth.Reachable
        runCurrent()
        assertThat(controller.state.value?.signalling).isEqualTo(MatrixRtcSignallingHealth.Reachable)
    }

    @Test
    fun `the host's connectivity report reaches the core`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)

        controller.reportConnectivity(isOnline = false)
        controller.reportConnectivity(isOnline = true)

        assertThat(rtcClient.connectivityReports).containsExactly(false, true).inOrder()
    }

    /** 034 C12: the core shows the hand at once and retries it, so a second tap is the reversal, sent at once. */
    @Test
    fun `a second tap while a raise is out lowers the hand, and the raise resolves quietly`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val rtcCall = rtcClient.lastCall!!
        rtcCall.lastMediaSession!!.localState.value = MatrixRtcLocalState(tile = aTile(A_LOCAL_MEMBER_ID), isScreenSharing = false)
        val homeserver = CompletableDeferred<Result<Unit>>()
        rtcCall.raiseHandResult = { homeserver.await() }

        controller.setHandRaised(true)
        runCurrent()
        controller.setHandRaised(false)
        runCurrent()

        assertThat(rtcCall.raiseHandCount).isEqualTo(1)
        assertThat(rtcCall.lowerHandCount).isEqualTo(1)

        // Superseded by the lower, as the core resolves it.
        homeserver.complete(Result.success(Unit))
        runCurrent()

        assertThat(controller.state.value?.handRequestFailure).isNull()
    }

    @Test
    fun `a refused request is reported until the user has seen it`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val rtcCall = rtcClient.lastCall!!
        rtcCall.lastMediaSession!!.localState.value = MatrixRtcLocalState(tile = aTile(A_LOCAL_MEMBER_ID, handPosition = 1), isScreenSharing = false)
        rtcCall.lowerHandResult = { Result.failure(IllegalStateException("refused")) }

        controller.setHandRaised(false)
        runCurrent()

        assertThat(controller.state.value?.handRequestFailure).isEqualTo(ElementCallHandRequestFailure.LOWER)

        controller.clearHandRequestFailure()
        runCurrent()

        assertThat(controller.state.value?.handRequestFailure).isNull()
    }

    @Test
    fun `no call joined yet sends nothing`() = runTest {
        val controller = createController(rtcClient = FakeMatrixRtcClient())

        controller.setHandRaised(true)
        runCurrent()

        assertThat(controller.state.value?.handRequestFailure).isNull()
    }

    private fun TestScope.createController(
        rtcClient: FakeMatrixRtcClient = FakeMatrixRtcClient(),
        audioDeviceController: FakeCallAudioDeviceController = FakeCallAudioDeviceController(),
        membershipFormat: MatrixRtcMembershipFormat = MatrixRtcMembershipFormat.CURRENT,
        platform: FakeElementCallPlatform = FakeElementCallPlatform(),
        audioFocus: FakeAudioFocus = FakeAudioFocus(requestAudioFocusResult = {}, releaseAudioFocusResult = {}),
        lifecycleListener: FakeElementCallLifecycleListener = FakeElementCallLifecycleListener(),
        isAudioCall: Boolean = true,
        isStartingCall: Boolean = true,
        ringLifetime: Duration? = null,
        roomIsDm: Boolean? = false,
        roomContextProvider: FakeElementCallRoomContextProvider = FakeElementCallRoomContextProvider(
            roomContext = roomIsDm?.let { ElementCallRoomContext(displayName = "A room", isDm = it, members = emptyMap()) },
        ),
        isScreenSharingEnabled: Boolean = false,
        participantCapForJoinLeaveSounds: Int = ElementCallOptions.DEFAULT_PARTICIPANT_CAP_FOR_JOIN_LEAVE_SOUNDS,
    ): DefaultElementCallController {
        val controller = DefaultElementCallController(
            scope = backgroundScope,
            platform = platform,
            rtcClient = rtcClient,
            audioDeviceController = audioDeviceController,
            audioFocus = audioFocus,
            lifecycleListener = lifecycleListener,
            roomContextProvider = roomContextProvider,
            options = ElementCallOptions(
                membershipFormat = membershipFormat,
                isScreenSharingEnabled = isScreenSharingEnabled,
                participantCapForJoinLeaveSounds = participantCapForJoinLeaveSounds,
                ringLifetime = ringLifetime,
            ),
        )
        controller.startCall(ElementCallData(roomId = A_ROOM_ID, isAudioCall = isAudioCall, isStartingCall = isStartingCall))
        runCurrent()
        return controller
    }
}
