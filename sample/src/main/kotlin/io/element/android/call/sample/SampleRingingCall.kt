/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import android.content.Context
import io.element.android.call.api.ElementCallController
import io.element.android.call.api.ElementCallData
import io.element.android.call.api.ElementCallOptions
import io.element.android.call.api.ElementCallRoomContext
import io.element.android.call.api.ElementCallRoomMember
import io.element.android.call.api.rtc.MatrixRtcCallEnd
import io.element.android.call.api.rtc.MatrixRtcCallStatus
import io.element.android.call.api.rtc.MatrixRtcLocalState
import io.element.android.call.api.rtc.MatrixRtcMediaPhase
import io.element.android.call.api.rtc.MatrixRtcRingPhase
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcTile
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.MatrixRtcTileKind
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.impl.ElementCallStack
import io.element.android.call.test.ElementCallTestPattern
import io.element.android.call.test.FakeElementCallMatrixTransport
import io.element.android.call.test.FakeElementCallRoomContextProvider
import io.element.android.call.test.FakeMatrixRtcCall
import io.element.android.call.test.FakeMatrixRtcClient
import io.element.android.call.test.FakeMatrixRtcMediaSession
import io.element.android.call.test.aRoster
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The ringing fixtures of feature-hq `harness/fixtures.md`: a DM call to Bob, played to an outcome at 6 s. */
enum class SampleRingingFixture(val key: String, val title: String, val description: String, val isAudioCall: Boolean, val outcome: Outcome) {
    PICKUP(
        key = "ringing_pickup",
        title = "Ringing, then picked up",
        description = "The ringing screen and the ringback; Bob joins with video and his picture replaces the avatar.",
        isAudioCall = false,
        outcome = Outcome.PICKUP,
    ),
    VOICE_PICKUP(
        key = "ringing_voice_pickup",
        title = "Ringing a voice call, then picked up",
        description = "A voice call's icon; Bob joins without video, on the same avatar.",
        isAudioCall = true,
        outcome = Outcome.PICKUP,
    ),
    DECLINED(
        key = "ringing_declined",
        title = "Ringing, then declined",
        description = "The screen closes at once and the decline's sound plays to its end.",
        isAudioCall = false,
        outcome = Outcome.DECLINE,
    ),
    UNANSWERED(
        key = "ringing_unanswered",
        title = "Ringing, unanswered",
        description = "The ring runs out: the screen closes and the timeout's sound plays to its end.",
        isAudioCall = false,
        outcome = Outcome.TIMEOUT,
    ),
    ;

    enum class Outcome { PICKUP, DECLINE, TIMEOUT }

    companion object {
        fun fromKey(key: String): SampleRingingFixture? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One ringing fixture through the real controller, the real screen and the real sounds, over a scripted core
 * and no homeserver: the ring goes out 0.5 s after the join, and the outcome comes 6 s later.
 */
class SampleRingingCall(context: Context, private val scope: CoroutineScope, private val fixture: SampleRingingFixture) {
    private val joined = CompletableDeferred<Unit>()
    private val call = FakeMatrixRtcCall(ROOM_ID, "m.call#room").apply {
        status.value = MatrixRtcCallStatus.Calling(BOB, MatrixRtcRingPhase.Sending, MatrixRtcMediaPhase.Connecting)
        newMediaSession = {
            FakeMatrixRtcMediaSession(frames = ::pattern).apply {
                localState.value = MatrixRtcLocalState(tile(ALICE, hasVideo = !fixture.isAudioCall), isScreenSharing = false)
            }
        }
    }
    private val stack = ElementCallStack.Builder(context, FakeElementCallMatrixTransport())
        .rtcClient(FakeMatrixRtcClient(joinResult = { Result.success(call).also { joined.complete(Unit) } }))
        .roomContext(FakeElementCallRoomContextProvider(ElementCallRoomContext(displayName = "Bob", isDm = true, members = MEMBERS)))
        .options(ElementCallOptions(ringLifetime = RING_LIFETIME))
        .build(scope)

    val controller: ElementCallController get() = stack.controller

    fun start() {
        controller.startCall(ElementCallData(roomId = ROOM_ID, isAudioCall = fixture.isAudioCall, isStartingCall = true))
        scope.launch { play() }
    }

    fun close() = stack.close()

    private suspend fun play() {
        joined.await()
        delay(RING_SENT_AFTER)
        val sentAt = System.currentTimeMillis()
        call.status.value = MatrixRtcCallStatus.Calling(
            BOB,
            MatrixRtcRingPhase.Ringing(sentAtMs = sentAt, lifetimeMs = RING_LIFETIME.inWholeMilliseconds),
            MatrixRtcMediaPhase.Connected(degraded = false),
        )
        delay(RING_LIFETIME)
        if (call.status.value !is MatrixRtcCallStatus.Calling) return
        call.status.value = when (fixture.outcome) {
            SampleRingingFixture.Outcome.PICKUP -> {
                call.lastMediaSession?.pushRoster(aRoster(tile(BOB_NAME, hasVideo = !fixture.isAudioCall)))
                MatrixRtcCallStatus.Connected(sinceMs = System.currentTimeMillis(), degraded = false)
            }
            SampleRingingFixture.Outcome.DECLINE -> MatrixRtcCallStatus.Ended(MatrixRtcCallEnd.Declined)
            SampleRingingFixture.Outcome.TIMEOUT -> MatrixRtcCallStatus.Ended(MatrixRtcCallEnd.Unanswered)
        }
    }

    /** Bob's camera is portrait, as the fixtures file has it, and ours is held upright. */
    @Suppress("UnusedParameter")
    private fun pattern(memberId: String, kind: MatrixRtcStreamKind): Flow<MatrixRtcVideoFrame> = ElementCallTestPattern.portrait().frames()

    private companion object {
        const val ALICE = "Alice"
        const val BOB_NAME = "Bob"
        val BOB = SampleFixture.userIdOf(BOB_NAME)
        val ROOM_ID = RoomId("!bob:example.com")
        val RING_SENT_AFTER = 500.milliseconds
        val RING_LIFETIME = 6.seconds
        val MEMBERS = listOf(ALICE, BOB_NAME).associate { name ->
            val userId = SampleFixture.userIdOf(name)
            userId to ElementCallRoomMember(userId = userId, displayName = name, avatarUrl = null)
        }

        fun tile(name: String, hasVideo: Boolean) = MatrixRtcTile(
            id = MatrixRtcTileId(SampleFixture.memberIdOf(name), MatrixRtcTileKind.PERSON),
            userId = SampleFixture.userIdOf(name),
            deviceId = "DEVICE",
            isHero = false,
            hasVideo = hasVideo,
            isMicrophoneMuted = false,
            isSpeaking = false,
            handRaisedAtMs = null,
            handRaisedPosition = null,
            isReachable = true,
        )
    }
}
