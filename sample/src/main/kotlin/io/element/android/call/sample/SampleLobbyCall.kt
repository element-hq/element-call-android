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
import io.element.android.call.api.ElementCallRoomContext
import io.element.android.call.api.ElementCallRoomMember
import io.element.android.call.api.rtc.MatrixRtcCallIntent
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
import io.element.android.call.test.FakeElementCallRoomContextProvider
import io.element.android.call.test.FakeMatrixBackend
import io.element.android.call.test.FakeMatrixRtcCall
import io.element.android.call.test.FakeMatrixRtcClient
import io.element.android.call.test.FakeMatrixRtcMediaSession
import io.element.android.call.test.FakeMatrixRtcRoom
import io.element.android.call.test.aRoster
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The lobby's fixtures of feature-hq `harness/fixtures.md` (045 contract C25), in a group room with Bob and Carol. */
enum class SampleLobbyFixture(
    val key: String,
    val title: String,
    val description: String,
    /** The host's intent; null joins without choosing. */
    val intent: MatrixRtcCallIntent?,
    /** What the members agree on, as the core reads it. */
    val membersIntent: MatrixRtcCallIntent? = null,
    /** The core's first count; null never reads the call. */
    val count: Int?,
    /** Who is in the call once joined, as an existing fixture names them. */
    val joinInto: List<String> = listOf(BOB_NAME, CAROL_NAME),
    /** How long the core takes to read the call. */
    val loadingTime: Duration = Duration.ZERO,
) {
    EMPTY(
        key = "lobby_empty",
        title = "Lobby, nobody in the call",
        description = "Start group call, no count; Start joins into `alone` and notifies.",
        intent = MatrixRtcCallIntent.VIDEO,
        count = 0,
        joinInto = emptyList(),
    ),
    IN_PROGRESS(
        key = "lobby_in_progress",
        title = "Lobby, call in progress",
        description = "Join call, three in the call.",
        intent = MatrixRtcCallIntent.VIDEO,
        count = 3,
    ),
    COUNT_CHANGES(
        key = "lobby_count_changes",
        title = "Lobby, people come and go",
        description = "0, then 2 at 5 s, then 0 at 10 s, looping: the label, the count and the ended notice follow (R42, R57).",
        intent = MatrixRtcCallIntent.VIDEO,
        count = 0,
    ),
    JOIN(
        key = "lobby_join",
        title = "Lobby, then join",
        description = "Join into `small_three`; our tile is reported 2 s after the tap, the window R49 is about.",
        intent = MatrixRtcCallIntent.VIDEO,
        count = 2,
    ),
    VOICE(
        key = "lobby_voice",
        title = "Lobby, voice call",
        description = "The host asked for voice: the camera starts off, the earpiece is the output.",
        intent = MatrixRtcCallIntent.AUDIO,
        count = 2,
    ),
    NO_INTENT_VOICE_CALL(
        key = "lobby_no_intent_voice_call",
        title = "Lobby, joining a voice call without choosing",
        description = "No host intent; the two members agree on voice, so the camera starts off. Join into `small_three`.",
        intent = null,
        membersIntent = MatrixRtcCallIntent.AUDIO,
        count = 2,
    ),
    MIC_REFUSED(
        key = "lobby_mic_refused",
        title = "Lobby, microphone refused",
        description = "The microphone answers refused, whatever the device says: Join disabled, the lobby says why (R27).",
        intent = MatrixRtcCallIntent.VIDEO,
        count = 1,
    ),
    LOADING(
        key = "lobby_loading",
        title = "Lobby, slow to read the call",
        description = "Loading for 3 s: no label, no count, no camera, nothing asked (R59). Join into `small_three`.",
        intent = MatrixRtcCallIntent.VIDEO,
        count = 2,
        loadingTime = 3.seconds,
    ),
    UNREADABLE(
        key = "lobby_unreadable",
        title = "Lobby, call cannot be read",
        description = "The room fails to open after 2 s: the failure screen (R60).",
        intent = MatrixRtcCallIntent.VIDEO,
        count = null,
        loadingTime = 2.seconds,
    ),
    ;

    companion object {
        fun fromKey(key: String): SampleLobbyFixture? = entries.firstOrNull { it.key == key }
    }
}

/** One lobby fixture through the real controller and the real screen, over a scripted core and no homeserver. */
class SampleLobbyCall(context: Context, private val scope: CoroutineScope, private val fixture: SampleLobbyFixture) : SampleLiveCall {
    private val roomGate = CompletableDeferred<Unit>()
    private val opened = CompletableDeferred<FakeMatrixRtcRoom>()
    private val joined = CompletableDeferred<Unit>()
    private var script: Job? = null

    private val call = FakeMatrixRtcCall(ROOM_ID, "m.call#room").apply {
        newMediaSession = {
            FakeMatrixRtcMediaSession(frames = ::pattern).apply {
                pushRoster(aRoster(*fixture.joinInto.map { tile(it) }.toTypedArray()))
                // Late on purpose in `lobby_join`: until the core reports us, our tile is the lobby's (R49).
                val report = if (fixture == SampleLobbyFixture.JOIN) OWN_TILE_REPORTED_AFTER else Duration.ZERO
                scope.launch {
                    delay(report)
                    localState.value = MatrixRtcLocalState(tile(ALICE), isScreenSharing = false)
                }
            }
        }
    }

    private val client = FakeMatrixRtcClient(
        roomResult = { if (fixture.count == null) Result.failure(IllegalStateException("the room could not be read")) else null },
        joinResult = { Result.success(call).also { joined.complete(Unit) } },
        membersIntent = fixture.membersIntent,
        roomGate = roomGate,
        onRoomOpened = { room ->
            room.memberCount.value = fixture.count
            opened.complete(room)
        },
        cameraFrames = { ElementCallTestPattern.portrait().frames() },
    )

    private val stack = ElementCallStack.Builder(context, FakeMatrixBackend(userId = SampleFixture.userIdOf(ALICE).value))
        .rtcClient(client)
        .roomContext(FakeElementCallRoomContextProvider(ElementCallRoomContext(displayName = "Lobby", isDm = false, members = MEMBERS)))
        .analytics(SampleAnalytics)
        .build(scope)

    /** The microphone fixture answers refused whatever the device has granted, so the refusal can be seen on any phone. */
    override val controller: ElementCallController = if (fixture == SampleLobbyFixture.MIC_REFUSED) {
        object : ElementCallController by stack.controller {
            override fun setMicrophonePermissionGranted(granted: Boolean) = stack.controller.setMicrophonePermissionGranted(false)
        }
    } else {
        stack.controller
    }

    override fun start() {
        controller.startCall(ElementCallData(roomId = ROOM_ID, intent = fixture.intent, isStartingCall = true, isDm = false))
        script = scope.launch {
            delay(fixture.loadingTime)
            roomGate.complete(Unit)
            if (fixture == SampleLobbyFixture.COUNT_CHANGES) loopCount(opened.await())
        }
    }

    override fun close() {
        script?.cancel()
        stack.close()
    }

    private suspend fun loopCount(room: FakeMatrixRtcRoom) {
        while (true) {
            delay(COUNT_STEP)
            room.memberCount.value = 2
            delay(COUNT_STEP)
            room.memberCount.value = 0
        }
    }

    @Suppress("UnusedParameter")
    private fun pattern(memberId: String, kind: MatrixRtcStreamKind): Flow<MatrixRtcVideoFrame> = ElementCallTestPattern.landscape().frames()

    private companion object {
        const val ALICE = "Alice"
        val ROOM_ID = RoomId("!lobby:example.com")
        val OWN_TILE_REPORTED_AFTER = 2.seconds
        val COUNT_STEP = 5.seconds
        val MEMBERS = listOf(ALICE, BOB_NAME, CAROL_NAME).associate { name ->
            val userId = SampleFixture.userIdOf(name)
            userId to ElementCallRoomMember(userId = userId, displayName = name, avatarUrl = null)
        }

        fun tile(name: String) = MatrixRtcTile(
            id = MatrixRtcTileId(SampleFixture.memberIdOf(name), MatrixRtcTileKind.PERSON),
            userId = SampleFixture.userIdOf(name),
            deviceId = "DEVICE",
            isHero = false,
            hasVideo = true,
            isMicrophoneMuted = false,
            isSpeaking = false,
            handRaisedAtMs = null,
            handRaisedPosition = null,
            connection = MatrixRtcConnectionState.CONNECTED,
            unableToDecrypt = null,
        )
    }
}

private const val BOB_NAME = "Bob"
private const val CAROL_NAME = "Carol"
