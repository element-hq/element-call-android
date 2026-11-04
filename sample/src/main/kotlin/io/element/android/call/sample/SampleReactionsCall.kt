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
import io.element.android.call.api.rtc.MatrixRtcCallEvent
import io.element.android.call.api.rtc.MatrixRtcCallStatus
import io.element.android.call.api.rtc.MatrixRtcConnectionState
import io.element.android.call.api.rtc.MatrixRtcLocalState
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcTile
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.MatrixRtcTileKind
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.impl.ElementCallStack
import io.element.android.call.test.ElementCallTestPattern
import io.element.android.call.test.FakeElementCallPreferencesStore
import io.element.android.call.test.FakeElementCallRoomContextProvider
import io.element.android.call.test.FakeMatrixBackend
import io.element.android.call.test.FakeMatrixRtcCall
import io.element.android.call.test.FakeMatrixRtcClient
import io.element.android.call.test.FakeMatrixRtcMediaSession
import io.element.android.call.test.aRoster
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The `reactions` fixture of feature-hq `harness/fixtures.md` (037): received reactions on a 22 s loop, pushed as the core's
 * events into the real controller, so the real catalogue, spread and sounds run (contract C3).
 *
 * [pace] is the loop's second: shorter for a stress run, `--es reactionInterval 0.05`.
 */
class SampleReactionsCall(context: Context, private val scope: CoroutineScope, private val pace: Duration = 1.seconds) : SampleLiveCall {
    private val joined = CompletableDeferred<Unit>()
    private val client = FakeMatrixRtcClient(joinResult = { Result.success(call).also { joined.complete(Unit) } })
    private val call: FakeMatrixRtcCall = FakeMatrixRtcCall(ROOM_ID, "m.call#room").apply {
        status.value = MatrixRtcCallStatus.Connected(sinceMs = System.currentTimeMillis(), degraded = false)
        newMediaSession = { FakeMatrixRtcMediaSession(frames = ::pattern) }
    }
    private val stack = ElementCallStack.Builder(context, FakeMatrixBackend())
        .rtcClient(client)
        .roomContext(FakeElementCallRoomContextProvider(ElementCallRoomContext(displayName = "Reactions", isDm = false, members = MEMBERS)))
        // In memory, so each opening starts from web's defaults, and with developer mode on so the whole sheet shows.
        .preferencesStore(FakeElementCallPreferencesStore())
        .options(ElementCallOptions(isDeveloperModeEnabled = true))
        .build(scope)

    override val controller: ElementCallController get() = stack.controller

    override fun start() {
        controller.startCall(ElementCallData(roomId = ROOM_ID, isAudioCall = false, isStartingCall = true))
        scope.launch { play() }
    }

    override fun close() = stack.close()

    private suspend fun play() {
        joined.await()
        // The session exists once media connects, a moment after the join.
        while (call.lastMediaSession == null) delay(50)
        val session = call.lastMediaSession!!
        session.localState.value = MatrixRtcLocalState(tile(session.localMemberId, OWN), isScreenSharing = false)
        session.pushRoster(aRoster(*SENDERS.map { tile(SampleFixture.memberIdOf(it), it) }.toTypedArray()))
        delay(pace)

        var next = 0
        while (true) {
            // 0 s to 14 s: one a second, the senders in turn, walking the catalogue from where the last loop stopped.
            for (second in 0..14) {
                val sender = (SENDERS + OWN)[second % (SENDERS.size + 1)]
                val (name, emoji, sound) = CATALOGUE[next++ % CATALOGUE.size]
                val memberId = if (sender == OWN) session.localMemberId else SampleFixture.memberIdOf(sender)
                session.emit(MatrixRtcCallEvent.Reaction(memberId, emoji, name, sound))
                delay(pace)
            }
            // 15 s to 17 s: nothing, so every member is outside the core's three-second window.
            delay(pace * 3)
            // 18 s: four at the same instant, the same sound overlapping itself (R2b, R10).
            SENDERS.forEach { session.emit(MatrixRtcCallEvent.Reaction(SampleFixture.memberIdOf(it), "👏", "clapping", "clap")) }
            delay(pace * 4)
        }
    }

    @Suppress("UnusedParameter")
    private fun pattern(memberId: String, kind: MatrixRtcStreamKind): Flow<MatrixRtcVideoFrame> = ElementCallTestPattern.landscape().frames()

    private companion object {
        const val OWN = "Alice"
        val ROOM_ID = RoomId("!reactions:example.com")
        val SENDERS = listOf("Carol", "Bob", "Dan", "Wolfeschlegelsteinhausenbergerdorff")
        val MEMBERS = (listOf(OWN) + SENDERS).associate { name ->
            val userId = SampleFixture.userIdOf(name)
            userId to ElementCallRoomMember(userId = userId, displayName = name, avatarUrl = null)
        }

        /** Element Call web's catalogue, in its picker's order, with the sound the core names for each. */
        val CATALOGUE = listOf(
            Triple("thumbsup", "👍", "generic"),
            Triple("party", "🎉", "party"),
            Triple("clapping", "👏", "clap"),
            Triple("dog", "🐶", "dog"),
            Triple("cat", "🐱", "cat"),
            Triple("lightbulb", "💡", "lightbulb"),
            Triple("crickets", "🦗", "crickets"),
            Triple("thumbsdown", "👎", "generic"),
            Triple("dizzy", "😵‍💫", "generic"),
            Triple("ok", "👌", "generic"),
            Triple("heart", "🥰", "generic"),
            Triple("laugh", "😄", "generic"),
            Triple("deer", "🦌", "deer"),
            Triple("rock", "🤘", "rock"),
            Triple("wave", "👋", "wave"),
            Triple("drum", "🥁", "baduntss"),
            Triple("bell", "🛎️", "bell"),
        )

        fun tile(memberId: String, name: String) = MatrixRtcTile(
            id = MatrixRtcTileId(memberId, MatrixRtcTileKind.PERSON),
            userId = SampleFixture.userIdOf(name),
            deviceId = "DEVICE",
            isHero = false,
            // Dan's camera is off, as fixtures.md has it.
            hasVideo = name != "Dan",
            isMicrophoneMuted = false,
            isSpeaking = false,
            handRaisedAtMs = null,
            handRaisedPosition = null,
            connection = MatrixRtcConnectionState.CONNECTED,
            unableToDecrypt = null,
        )
    }
}
