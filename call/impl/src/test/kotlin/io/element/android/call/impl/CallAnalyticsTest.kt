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
import io.element.android.call.api.ElementCallEndReason
import io.element.android.call.api.ElementCallFailure
import io.element.android.call.api.ElementCallOptions
import io.element.android.call.api.ElementCallRoomContext
import io.element.android.call.api.ElementCallRoomContextProvider
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.NoOpElementCallRoomContextProvider
import io.element.android.call.api.analytics.ElementCallAnalytics
import io.element.android.call.api.analytics.ElementCallAnalyticsCallKind
import io.element.android.call.api.analytics.ElementCallAnalyticsCallKind.DM
import io.element.android.call.api.analytics.ElementCallAnalyticsCallKind.GROUP
import io.element.android.call.api.analytics.ElementCallAnalyticsEvent
import io.element.android.call.api.analytics.ElementCallAnalyticsEvent.CallFailure
import io.element.android.call.api.analytics.ElementCallAnalyticsEvent.CallJoinAttempted
import io.element.android.call.api.analytics.ElementCallAnalyticsEvent.CallJoinOutcome
import io.element.android.call.api.analytics.ElementCallAnalyticsEvent.CallParticipationEnded
import io.element.android.call.api.analytics.ElementCallAnalyticsFailureCause
import io.element.android.call.api.analytics.ElementCallAnalyticsFailurePhase
import io.element.android.call.api.analytics.ElementCallAnalyticsFailureStage
import io.element.android.call.api.analytics.ElementCallAnalyticsFailureStep
import io.element.android.call.api.analytics.ElementCallAnalyticsOutcome
import io.element.android.call.api.analytics.NoOpElementCallAnalytics
import io.element.android.call.api.rtc.MatrixRtcCallEnd
import io.element.android.call.api.rtc.MatrixRtcCallEvent
import io.element.android.call.api.rtc.MatrixRtcCallIntent
import io.element.android.call.api.rtc.MatrixRtcCallKind
import io.element.android.call.api.rtc.MatrixRtcCallStatus
import io.element.android.call.api.rtc.MatrixRtcConnectionLossReason
import io.element.android.call.api.rtc.MatrixRtcJoinException
import io.element.android.call.api.rtc.MatrixRtcMediaPhase
import io.element.android.call.api.rtc.MatrixRtcRingPhase
import io.element.android.call.api.rtc.id.UserId
import io.element.android.call.test.A_DEVICE_ID
import io.element.android.call.test.A_ROOM_ID
import io.element.android.call.test.A_USER_ID
import io.element.android.call.test.FakeElementCallAnalytics
import io.element.android.call.test.FakeElementCallLifecycleListener
import io.element.android.call.test.FakeElementCallPreferencesStore
import io.element.android.call.test.FakeElementCallRoomContextProvider
import io.element.android.call.test.FakeMatrixRtcCall
import io.element.android.call.test.FakeMatrixRtcClient
import io.element.android.call.test.FakeMatrixRtcMediaSession
import io.element.android.call.test.audio.FakeAudioFocus
import io.element.android.call.test.audio.FakeCallAudioDeviceController
import io.element.android.call.tests.testutils.WarmUpRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Feature-hq 044 as the call reports it: the real controller over the fakes, with a recording receiver. One test per
 * row of the plan's emission and failure tables, and the rules that are about the call rather than the state machine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallAnalyticsTest {
    @get:Rule val warmUpRule = WarmUpRule()

    private val analytics = FakeElementCallAnalytics()

    // region Attempted and outcome

    @Test
    fun `a group call that connects and is hung up reports attempted, connected, then the end`() = runTest {
        val platform = FakeElementCallPlatform(elapsedRealtimeMs = 1_000)
        val controller = createController(platform = platform)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Connected)

        platform.elapsedRealtimeMs = 61_000
        controller.hangUp()
        runCurrent()

        assertThat(analytics.anonymous()).containsExactly(
            attempted(GROUP),
            outcome(ElementCallAnalyticsOutcome.CONNECTED),
            CallParticipationEnded(ID, GROUP, ElementCallEndReason.LEFT, durationMs = 60_000, participantsMax = 1),
        ).inOrder()
        assertThat(analytics.events.map { it.attemptId }.toSet()).hasSize(1)
    }

    @Test
    fun `the DM decision is the join's, reported before the join is made`() = runTest {
        val joinGate = CompletableDeferred<Unit>()
        val rtcClient = FakeMatrixRtcClient(joinGate = joinGate)
        var joinsWhenAttempted: Int? = null
        val analytics = FakeElementCallAnalytics { if (it is CallJoinAttempted) joinsWhenAttempted = rtcClient.lastRoom?.joinCallCount }
        val controller = createController(rtcClient = rtcClient, analytics = analytics, isDm = true)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(analytics.anonymous()).containsExactly(attempted(DM))
        assertThat(joinsWhenAttempted).isEqualTo(0)
        assertThat(rtcClient.lastRoom?.lastKind).isInstanceOf(MatrixRtcCallKind.Direct::class.java)
        joinGate.complete(Unit)
    }

    @Test
    fun `the DM flag is the host's, so a provider that never answers holds nothing back`() = runTest {
        val provider = FakeElementCallRoomContextProvider(roomContext = null)
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient, roomContextProvider = provider)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(analytics.anonymous().first()).isEqualTo(attempted(GROUP))
        assertThat(rtcClient.lastRoom?.lastKind).isInstanceOf(MatrixRtcCallKind.Group::class.java)
    }

    @Test
    fun `a room context that disagrees with the host's DM flag changes nothing`() = runTest {
        val provider = FakeElementCallRoomContextProvider(roomContext = null)
        val controller = createController(roomContextProvider = provider)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        provider.roomContext.value = aRoomContext(isDm = true)
        runCurrent()
        assertThat(controller.state.value?.isDm).isFalse()
        controller.hangUp()
        runCurrent()

        assertThat(analytics.names).containsExactly("CallJoinAttempted", "CallJoinOutcome", "CallParticipationEnded").inOrder()
        assertThat(analytics.events.map { it.callKind }.toSet()).containsExactly(GROUP)
    }

    @Test
    fun `a refused microphone is its own outcome, not a failure`() = runTest {
        val controller = createController(isDm = true)
        controller.setMicrophonePermissionGranted(false)
        runCurrent()

        assertThat(analytics.anonymous()).containsExactly(attempted(DM), outcome(ElementCallAnalyticsOutcome.PERMISSION_REFUSED, DM)).inOrder()
        controller.hangUp()
        runCurrent()
        assertThat(analytics.events).hasSize(2)
    }

    @Test
    fun `the call does not wait on analytics, and a refusal with no room context yet is a group call`() = runTest {
        val controller = createController(roomContextProvider = FakeElementCallRoomContextProvider(roomContext = null))
        controller.setMicrophonePermissionGranted(false)
        runCurrent()
        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Failed(ElementCallFailure.MicrophoneRefused))
        assertThat(analytics.anonymous()).containsExactly(attempted(GROUP), outcome(ElementCallAnalyticsOutcome.PERMISSION_REFUSED)).inOrder()

        controller.hangUp()
        runCurrent()
        assertThat(controller.state.value).isNull()
        assertThat(analytics.events).hasSize(2)
    }

    @Test
    fun `a hang-up before the join decides takes the room's kind from the snapshot`() = runTest {
        val controller = createController(isDm = true)
        assertThat(controller.state.value?.isDm).isTrue()
        controller.hangUp()
        runCurrent()
        assertThat(analytics.anonymous()).containsExactly(attempted(DM), outcome(ElementCallAnalyticsOutcome.ABANDONED, DM)).inOrder()
    }

    @Test
    fun `a hang-up before anything is known of the room is a group call`() = runTest {
        val controller = createController(roomContextProvider = FakeElementCallRoomContextProvider(roomContext = null))
        controller.hangUp()
        runCurrent()
        assertThat(controller.state.value).isNull()
        assertThat(analytics.anonymous()).containsExactly(attempted(GROUP), outcome(ElementCallAnalyticsOutcome.ABANDONED)).inOrder()
    }

    @Test
    fun `a provider with no room knowledge at all makes a refusal a group call`() = runTest {
        val controller = createController(roomContextProvider = null)
        controller.setMicrophonePermissionGranted(false)
        runCurrent()
        assertThat(analytics.anonymous()).containsExactly(attempted(GROUP), outcome(ElementCallAnalyticsOutcome.PERMISSION_REFUSED)).inOrder()
    }

    @Test
    fun `a room that does not open is a join failure at the homeserver`() = runTest {
        val rtcClient = FakeMatrixRtcClient(roomResult = { Result.failure(IllegalStateException("seeding timed out")) })
        val controller = createController(rtcClient = rtcClient, isDm = true)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(analytics.anonymous()).containsExactly(
            attempted(DM),
            outcome(ElementCallAnalyticsOutcome.FAILED, DM),
            joiningFailure(
                ElementCallAnalyticsFailureCause.OTHER,
                ElementCallAnalyticsFailureStage.HOMESERVER,
                ElementCallAnalyticsFailureStep.UNKNOWN,
                kind = DM
            ),
        ).inOrder()
    }

    @Test
    fun `a hang-up while joining is abandoned with no end reason`() = runTest {
        val joinGate = CompletableDeferred<Unit>()
        val controller = createController(rtcClient = FakeMatrixRtcClient(joinGate = joinGate))
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        controller.hangUp()
        runCurrent()

        assertThat(analytics.anonymous()).containsExactly(attempted(GROUP), outcome(ElementCallAnalyticsOutcome.ABANDONED)).inOrder()
    }

    @Test
    fun `an outgoing DM connects while it rings, and an unanswered ring is an end, not a failure`() = runTest {
        val callee = UserId("@callee:example.org")
        val call = FakeMatrixRtcCall(A_ROOM_ID, "m.call#room").apply {
            status.value = MatrixRtcCallStatus.Calling(callee, MatrixRtcRingPhase.Sending, MatrixRtcMediaPhase.Connecting)
        }
        val platform = FakeElementCallPlatform(elapsedRealtimeMs = 1_000)
        val controller = createController(rtcClient = FakeMatrixRtcClient(joinResult = { Result.success(call) }), isDm = true, platform = platform)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        // The microphone is published, our media is not up yet.
        assertThat(analytics.anonymous()).containsExactly(attempted(DM))

        platform.elapsedRealtimeMs = 2_000
        call.status.value = MatrixRtcCallStatus.Calling(callee, MatrixRtcRingPhase.Ringing(0L, 90_000L), MatrixRtcMediaPhase.Connected(degraded = false))
        runCurrent()
        assertThat(analytics.anonymous().last()).isEqualTo(outcome(ElementCallAnalyticsOutcome.CONNECTED, DM))
        assertThat(controller.state.value?.connection).isNotEqualTo(ElementCallConnection.Connected)

        platform.elapsedRealtimeMs = 92_000
        call.status.value = MatrixRtcCallStatus.Ended(MatrixRtcCallEnd.Unanswered)
        runCurrent()
        assertThat(analytics.anonymous()).containsExactly(
            attempted(DM),
            outcome(ElementCallAnalyticsOutcome.CONNECTED, DM),
            CallParticipationEnded(ID, DM, ElementCallEndReason.UNANSWERED, durationMs = 90_000, participantsMax = 1),
        ).inOrder()
    }

    @Test
    fun `each end from the other side before connected is abandoned with its reason, and no failure`() = runTest {
        val ends = mapOf(
            MatrixRtcCallEnd.Declined to ElementCallEndReason.DECLINED,
            MatrixRtcCallEnd.Unanswered to ElementCallEndReason.UNANSWERED,
            MatrixRtcCallEnd.RingNotSent to ElementCallEndReason.RING_NOT_SENT,
            MatrixRtcCallEnd.OthersLeft to ElementCallEndReason.OTHERS_LEFT,
            MatrixRtcCallEnd.Empty to ElementCallEndReason.EMPTY,
            MatrixRtcCallEnd.SlotClosed to ElementCallEndReason.SLOT_CLOSED,
        )
        ends.forEach { (end, reason) ->
            val analytics = FakeElementCallAnalytics()
            val call = aRingingCall()
            val controller = createController(rtcClient = FakeMatrixRtcClient(joinResult = { Result.success(call) }), analytics = analytics, isDm = true)
            controller.setMicrophonePermissionGranted(true)
            runCurrent()
            call.status.value = MatrixRtcCallStatus.Ended(end)
            runCurrent()
            // A ring not sent stays on screen until the user closes it.
            controller.hangUp()
            runCurrent()

            assertThat(analytics.anonymous()).containsExactly(attempted(DM), outcome(ElementCallAnalyticsOutcome.ABANDONED, DM, reason)).inOrder()
        }
    }

    @Test
    fun `a plain leave from the core before connected is ours, abandoned with no end reason`() = runTest {
        val call = aRingingCall()
        val controller = createController(rtcClient = FakeMatrixRtcClient(joinResult = { Result.success(call) }), isDm = true)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        call.status.value = MatrixRtcCallStatus.Ended(MatrixRtcCallEnd.Left)
        runCurrent()

        assertThat(analytics.anonymous()).containsExactly(attempted(DM), outcome(ElementCallAnalyticsOutcome.ABANDONED, DM)).inOrder()
    }

    // endregion

    // region Failures

    @Test
    fun `each join error reports its cause, stage and step`() = runTest {
        val homeserver = ElementCallAnalyticsFailureStage.HOMESERVER
        val rows = listOf(
            MatrixRtcJoinException.TimedOut() to
                joiningFailure(ElementCallAnalyticsFailureCause.JOIN_TIMED_OUT, homeserver, ElementCallAnalyticsFailureStep.UNKNOWN),
            MatrixRtcJoinException.Refused(errcode = "M_LIMIT_EXCEEDED", reason = "slow down") to
                joiningFailure(
                    ElementCallAnalyticsFailureCause.JOIN_REFUSED,
                    homeserver,
                    ElementCallAnalyticsFailureStep.UNKNOWN,
                    errcode = "M_LIMIT_EXCEEDED"
                ),
            MatrixRtcJoinException.Refused(errcode = null, reason = "no") to
                joiningFailure(ElementCallAnalyticsFailureCause.JOIN_REFUSED, homeserver, ElementCallAnalyticsFailureStep.UNKNOWN),
            MatrixRtcJoinException.Refused(errcode = "not a code", reason = "no") to
                joiningFailure(ElementCallAnalyticsFailureCause.JOIN_REFUSED, homeserver, ElementCallAnalyticsFailureStep.UNKNOWN),
            MatrixRtcJoinException.SlotClosed() to
                joiningFailure(ElementCallAnalyticsFailureCause.SLOT_CLOSED, homeserver, ElementCallAnalyticsFailureStep.MEMBERSHIP),
            MatrixRtcJoinException.NoLiveKitTransport("livekit") to
                joiningFailure(ElementCallAnalyticsFailureCause.NO_MEDIA_SERVER, homeserver, ElementCallAnalyticsFailureStep.TRANSPORT_DISCOVERY),
            MatrixRtcJoinException.TokenEndpointUnsupported() to
                joiningFailure(
                    ElementCallAnalyticsFailureCause.TOKEN_ENDPOINT_UNSUPPORTED,
                    ElementCallAnalyticsFailureStage.MEDIA,
                    ElementCallAnalyticsFailureStep.MEDIA_TOKEN
                ),
            IllegalStateException("the core broke") to
                joiningFailure(ElementCallAnalyticsFailureCause.OTHER, homeserver, ElementCallAnalyticsFailureStep.UNKNOWN),
        )
        rows.forEach { (error, failure) ->
            val analytics = FakeElementCallAnalytics()
            val controller = createController(rtcClient = FakeMatrixRtcClient(joinResult = { Result.failure(error) }), analytics = analytics)
            controller.setMicrophonePermissionGranted(true)
            runCurrent()
            controller.hangUp()
            runCurrent()

            assertThat(analytics.anonymous()).containsExactly(attempted(GROUP), outcome(ElementCallAnalyticsOutcome.FAILED), failure).inOrder()
        }
    }

    @Test
    fun `a refused join keeps the homeserver's code on the failure the screen shows and on the event`() = runTest {
        val rtcClient = FakeMatrixRtcClient(joinResult = { Result.failure(MatrixRtcJoinException.Refused(errcode = "M_FORBIDDEN", reason = "not allowed")) })
        val controller = createController(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(controller.state.value?.connection).isEqualTo(ElementCallConnection.Failed(ElementCallFailure.JoinRefused("M_FORBIDDEN")))
        assertThat((analytics.events.last() as CallFailure).errcode).isEqualTo("M_FORBIDDEN")
        assertThat(analytics.events.last().properties["errcode"]).isEqualTo("M_FORBIDDEN")
    }

    @Test
    fun `media that does not attach is a join failure at the media server`() = runTest {
        val call = FakeMatrixRtcCall(A_ROOM_ID, "m.call#room").apply { connectMediaFailure = IllegalStateException("transport") }
        val controller = createController(rtcClient = FakeMatrixRtcClient(joinResult = { Result.success(call) }))
        controller.setMicrophonePermissionGranted(true)
        runCurrent()

        assertThat(analytics.anonymous()).containsExactly(
            attempted(GROUP),
            outcome(ElementCallAnalyticsOutcome.FAILED),
            joiningFailure(ElementCallAnalyticsFailureCause.OTHER, ElementCallAnalyticsFailureStage.MEDIA, ElementCallAnalyticsFailureStep.UNKNOWN),
        ).inOrder()
    }

    @Test
    fun `a microphone that will not publish is a join failure once the grace has passed, never connected`() = runTest {
        val session = FakeMatrixRtcMediaSession().apply { publishMicrophoneResult = { Result.failure(IllegalStateException("no mic")) } }
        val call = FakeMatrixRtcCall(A_ROOM_ID, "m.call#room").apply { newMediaSession = { session } }
        val controller = createController(rtcClient = FakeMatrixRtcClient(joinResult = { Result.success(call) }))
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        // Our media is up; the publish waits out the grace for the core's own end.
        assertThat(analytics.anonymous()).containsExactly(attempted(GROUP))

        advanceTimeBy(DefaultElementCallController.PUBLISH_FAILURE_GRACE + 1.milliseconds)
        assertThat(analytics.anonymous()).containsExactly(
            attempted(GROUP),
            outcome(ElementCallAnalyticsOutcome.FAILED),
            joiningFailure(
                ElementCallAnalyticsFailureCause.MICROPHONE_FAILED,
                ElementCallAnalyticsFailureStage.MEDIA,
                ElementCallAnalyticsFailureStep.MEDIA_CONNECTION
            ),
        ).inOrder()
    }

    @Test
    fun `a microphone that fails as the core gives the media server up reports the core's end`() = runTest {
        val session = FakeMatrixRtcMediaSession().apply { publishMicrophoneResult = { Result.failure(IllegalStateException("gone")) } }
        val call = FakeMatrixRtcCall(A_ROOM_ID, "m.call#room").apply { newMediaSession = { session } }
        val controller = createController(rtcClient = FakeMatrixRtcClient(joinResult = { Result.success(call) }))
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        call.status.value = MatrixRtcCallStatus.Ended(MatrixRtcCallEnd.ConnectionClosed(MatrixRtcConnectionLossReason.REFUSED, "403 from the SFU"))
        advanceTimeBy(3.seconds)

        assertThat(analytics.anonymous()).containsExactly(
            attempted(GROUP),
            outcome(ElementCallAnalyticsOutcome.FAILED),
            joiningFailure(
                ElementCallAnalyticsFailureCause.CONNECTION_LOST_REFUSED,
                ElementCallAnalyticsFailureStage.MEDIA,
                ElementCallAnalyticsFailureStep.UNKNOWN
            ),
        ).inOrder()
    }

    @Test
    fun `a media server that refuses us before our media connects is a join failure, not a call lost`() = runTest {
        val reasons = mapOf(
            MatrixRtcConnectionLossReason.REFUSED to (ElementCallAnalyticsFailureCause.CONNECTION_LOST_REFUSED to ElementCallAnalyticsFailureStep.UNKNOWN),
            MatrixRtcConnectionLossReason.NEVER_CONNECTED to
                (ElementCallAnalyticsFailureCause.CONNECTION_LOST_NEVER_CONNECTED to ElementCallAnalyticsFailureStep.UNKNOWN),
            MatrixRtcConnectionLossReason.BUDGET to (ElementCallAnalyticsFailureCause.CONNECTION_LOST_BUDGET to ElementCallAnalyticsFailureStep.UNKNOWN),
            MatrixRtcConnectionLossReason.TOKEN_ENDPOINT_UNSUPPORTED to
                (ElementCallAnalyticsFailureCause.TOKEN_ENDPOINT_UNSUPPORTED to ElementCallAnalyticsFailureStep.MEDIA_TOKEN),
        )
        reasons.forEach { (reason, expected) ->
            val analytics = FakeElementCallAnalytics()
            val call = aRingingCall()
            val controller = createController(rtcClient = FakeMatrixRtcClient(joinResult = { Result.success(call) }), analytics = analytics, isDm = true)
            controller.setMicrophonePermissionGranted(true)
            runCurrent()
            call.status.value = MatrixRtcCallStatus.Ended(MatrixRtcCallEnd.ConnectionClosed(reason, "lost"))
            runCurrent()
            controller.hangUp()
            runCurrent()

            assertThat(analytics.anonymous()).containsExactly(
                attempted(DM),
                outcome(ElementCallAnalyticsOutcome.FAILED, DM),
                joiningFailure(expected.first, ElementCallAnalyticsFailureStage.MEDIA, expected.second, kind = DM),
            ).inOrder()
        }
    }

    @Test
    fun `a connection lost after connected is an in-call failure, then the end, and dismissing reports nothing more`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val platform = FakeElementCallPlatform(elapsedRealtimeMs = 0)
        val controller = createController(rtcClient = rtcClient, platform = platform)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        platform.elapsedRealtimeMs = 5_000
        rtcClient.lastCall!!.status.value = MatrixRtcCallStatus.Ended(MatrixRtcCallEnd.ConnectionClosed(MatrixRtcConnectionLossReason.REFUSED, "lost"))
        runCurrent()
        assertThat(controller.state.value?.connection)
            .isEqualTo(ElementCallConnection.Failed(ElementCallFailure.ConnectionLost(MatrixRtcConnectionLossReason.REFUSED)))

        // The user reads the error for a minute; that is not call time.
        platform.elapsedRealtimeMs = 65_000
        controller.hangUp()
        runCurrent()

        assertThat(analytics.anonymous()).containsExactly(
            attempted(GROUP),
            outcome(ElementCallAnalyticsOutcome.CONNECTED),
            CallFailure(ID, GROUP, ElementCallAnalyticsFailurePhase.IN_CALL, ElementCallAnalyticsFailureCause.CONNECTION_LOST_REFUSED, null, null, null),
            CallParticipationEnded(ID, GROUP, ElementCallEndReason.CONNECTION_CLOSED, durationMs = 5_000, participantsMax = 1),
        ).inOrder()
    }

    @Test
    fun `a slot closed after connected is an end and no failure`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        rtcClient.lastCall!!.status.value = MatrixRtcCallStatus.Ended(MatrixRtcCallEnd.SlotClosed)
        runCurrent()

        assertThat(analytics.anonymous()).containsExactly(
            attempted(GROUP),
            outcome(ElementCallAnalyticsOutcome.CONNECTED),
            CallParticipationEnded(ID, GROUP, ElementCallEndReason.SLOT_CLOSED, durationMs = 0, participantsMax = 1),
        ).inOrder()
    }

    // endregion

    // region Ending

    @Test
    fun `participantsMax is the membership at connected, raised by every join and leave`() = runTest {
        val call = FakeMatrixRtcCall(A_ROOM_ID, "m.call#room").apply { memberCount.value = 3 }
        val controller = createController(rtcClient = FakeMatrixRtcClient(joinResult = { Result.success(call) }))
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val session = call.lastMediaSession!!
        session.emit(MatrixRtcCallEvent.ParticipantJoined("m1", UserId("@bob:example.org"), participantCount = 5))
        session.emit(MatrixRtcCallEvent.ParticipantLeft("m1", participantCount = 2))
        runCurrent()
        controller.hangUp()
        runCurrent()

        assertThat((analytics.events.last() as CallParticipationEnded).participantsMax).isEqualTo(5)
    }

    @Test
    fun `the end is reported before the media is disconnected, the call left and the state cleared`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        lateinit var controller: DefaultElementCallController
        val atEnd = mutableListOf<Any?>()
        val analytics = FakeElementCallAnalytics {
            if (it is CallParticipationEnded) {
                atEnd += rtcClient.lastCall?.lastMediaSession?.disconnectCount
                atEnd += rtcClient.lastCall?.leaveCount
                atEnd += rtcClient.lastRoom?.shutdownCount
                atEnd += controller.state.value != null
            }
        }
        controller = createController(rtcClient = rtcClient, analytics = analytics)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        controller.hangUp()
        runCurrent()

        assertThat(atEnd).containsExactly(0, 0, 0, true).inOrder()
        assertThat(rtcClient.lastCall?.leaveCount).isEqualTo(1)
        assertThat(controller.state.value).isNull()
    }

    @Test
    fun `a call discarded with its scope reports no end`() = runTest {
        val sessionScope = CoroutineScope(backgroundScope.coroutineContext + Job())
        val controller = createController(scope = sessionScope)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        sessionScope.cancel()
        runCurrent()

        assertThat(analytics.names).containsExactly("CallJoinAttempted", "CallJoinOutcome").inOrder()
    }

    // endregion

    // region No duplicates, no timers, nothing identifying

    @Test
    fun `repeated reports from a recreated screen do not repeat an event`() = runTest {
        val denied = createController(analytics = analytics)
        denied.setMicrophonePermissionGranted(false)
        denied.setMicrophonePermissionGranted(false)
        runCurrent()
        denied.hangUp()
        denied.hangUp()
        runCurrent()
        assertThat(analytics.names).containsExactly("CallJoinAttempted", "CallJoinOutcome").inOrder()

        val granted = FakeElementCallAnalytics()
        val controller = createController(analytics = granted, start = false)
        val callData = ElementCallData(roomId = A_ROOM_ID, intent = MatrixRtcCallIntent.AUDIO, isStartingCall = true, isDm = false, skipsLobby = true)
        controller.startCall(callData)
        controller.startCall(callData)
        runCurrent()
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        controller.setMicrophonePermissionGranted(true)
        controller.setInPictureInPicture(true)
        controller.setInPictureInPicture(false)
        controller.setMaximized(false)
        runCurrent()
        assertThat(granted.names).containsExactly("CallJoinAttempted", "CallJoinOutcome").inOrder()
    }

    @Test
    fun `a hang-up and the far end ending at once report one end`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        rtcClient.lastCall!!.status.value = MatrixRtcCallStatus.Ended(MatrixRtcCallEnd.OthersLeft)
        controller.hangUp()
        runCurrent()

        assertThat(analytics.names).containsExactly("CallJoinAttempted", "CallJoinOutcome", "CallParticipationEnded").inOrder()
        assertThat((analytics.events.last() as CallParticipationEnded).endReason).isEqualTo(ElementCallEndReason.OTHERS_LEFT)
    }

    @Test
    fun `nothing is reported on a timer`() = runTest {
        val controller = createController()
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        val reported = analytics.events.size
        advanceTimeBy(1.hours)
        assertThat(analytics.events).hasSize(reported)
    }

    @Test
    fun `the call runs the same with no receiver as with one`() = runTest {
        suspend fun TestScope.snapshots(analytics: ElementCallAnalytics): List<ElementCallSnapshot?> {
            val seen = mutableListOf<ElementCallSnapshot?>()
            val rtcClient = FakeMatrixRtcClient()
            val controller = createController(rtcClient = rtcClient, analytics = analytics, start = false)
            val job = backgroundScope.launch { controller.state.collect { seen += it } }
            controller.startCall(
                ElementCallData(roomId = A_ROOM_ID, intent = MatrixRtcCallIntent.AUDIO, isStartingCall = true, isDm = false, skipsLobby = true)
            )
            runCurrent()
            controller.setMicrophonePermissionGranted(true)
            runCurrent()
            rtcClient.lastCall!!.lastMediaSession!!.emit(MatrixRtcCallEvent.ParticipantJoined("m1", UserId("@bob:example.org"), participantCount = 2))
            runCurrent()
            controller.hangUp()
            runCurrent()
            job.cancel()
            return seen
        }
        val withNone = snapshots(NoOpElementCallAnalytics)
        val withOne = snapshots(FakeElementCallAnalytics())
        val throwing = snapshots { error("the host broke") }

        assertThat(withOne).isEqualTo(withNone)
        assertThat(throwing).isEqualTo(withNone)
    }

    @Test
    fun `no event carries the room, the user, the device or a member`() = runTest {
        val rtcClient = FakeMatrixRtcClient()
        val controller = createController(rtcClient = rtcClient, isDm = true)
        controller.setMicrophonePermissionGranted(true)
        runCurrent()
        rtcClient.lastCall!!.lastMediaSession!!.emit(MatrixRtcCallEvent.ParticipantJoined("aRemoteMemberId", A_USER_ID, participantCount = 2))
        rtcClient.lastCall!!.status.value =
            MatrixRtcCallStatus.Ended(MatrixRtcCallEnd.ConnectionClosed(MatrixRtcConnectionLossReason.BUDGET, "!aRoomId:domain"))
        runCurrent()
        controller.hangUp()
        runCurrent()

        val values = analytics.events.flatMap { it.properties.values }.map { it.toString() }
        val identifiers = listOf(A_ROOM_ID.value, A_USER_ID.value, A_DEVICE_ID.value, "aLocalMemberId", "aRemoteMemberId", "A room", "domain", "server.org")
        assertThat(analytics.events).hasSize(4)
        identifiers.forEach { identifier -> values.forEach { assertThat(it).doesNotContain(identifier) } }
    }

    // endregion

    private fun aRoomContext(isDm: Boolean) = ElementCallRoomContext(displayName = "A room", isDm = isDm, members = emptyMap())

    private fun aRingingCall() = FakeMatrixRtcCall(A_ROOM_ID, "m.call#room").apply {
        status.value = MatrixRtcCallStatus.Calling(
            UserId("@callee:example.org"),
            MatrixRtcRingPhase.Ringing(0L, 90_000L),
            MatrixRtcMediaPhase.Connecting,
        )
    }

    private fun TestScope.createController(
        rtcClient: FakeMatrixRtcClient = FakeMatrixRtcClient(),
        analytics: ElementCallAnalytics = this@CallAnalyticsTest.analytics,
        isDm: Boolean = false,
        roomContextProvider: ElementCallRoomContextProvider? = FakeElementCallRoomContextProvider(aRoomContext(isDm)),
        platform: FakeElementCallPlatform = FakeElementCallPlatform(),
        scope: CoroutineScope = backgroundScope,
        start: Boolean = true,
    ): DefaultElementCallController {
        val controller = DefaultElementCallController(
            scope = scope,
            platform = platform,
            rtcClient = rtcClient,
            audioDeviceController = FakeCallAudioDeviceController(),
            audioFocus = FakeAudioFocus(requestAudioFocusResult = {}, releaseAudioFocusResult = {}),
            lifecycleListener = FakeElementCallLifecycleListener(),
            roomContextProvider = roomContextProvider ?: NoOpElementCallRoomContextProvider,
            options = ElementCallOptions(),
            preferencesStore = FakeElementCallPreferencesStore(),
            analytics = analytics,
            ownUserId = A_USER_ID,
        )
        if (start) {
            controller.startCall(ElementCallData(roomId = A_ROOM_ID, intent = MatrixRtcCallIntent.AUDIO, isStartingCall = true, isDm = isDm, skipsLobby = true))
            runCurrent()
        }
        return controller
    }

    private companion object {
        const val ID = "id"

        fun attempted(kind: ElementCallAnalyticsCallKind) = CallJoinAttempted(ID, kind)

        fun outcome(outcome: ElementCallAnalyticsOutcome, kind: ElementCallAnalyticsCallKind = GROUP, endReason: ElementCallEndReason? = null) =
            CallJoinOutcome(ID, kind, outcome, endReason)

        fun joiningFailure(
            cause: ElementCallAnalyticsFailureCause,
            stage: ElementCallAnalyticsFailureStage,
            step: ElementCallAnalyticsFailureStep,
            errcode: String? = null,
            kind: ElementCallAnalyticsCallKind = GROUP,
        ) = CallFailure(ID, kind, ElementCallAnalyticsFailurePhase.JOINING, cause, stage, step, errcode)

        /** The events with their random id replaced, to compare whole. */
        fun FakeElementCallAnalytics.anonymous(): List<ElementCallAnalyticsEvent> = events.map {
            when (it) {
                is CallJoinAttempted -> it.copy(attemptId = ID)
                is CallJoinOutcome -> it.copy(attemptId = ID)
                is CallFailure -> it.copy(attemptId = ID)
                is CallParticipationEnded -> it.copy(attemptId = ID)
            }
        }
    }
}
