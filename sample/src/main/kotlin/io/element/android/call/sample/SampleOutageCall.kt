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
import io.element.android.call.api.rtc.MatrixRtcCallEnd
import io.element.android.call.api.rtc.MatrixRtcCallStatus
import io.element.android.call.api.rtc.MatrixRtcConnectionLossReason
import io.element.android.call.api.rtc.MatrixRtcConnectionState
import io.element.android.call.api.rtc.MatrixRtcInterruptions
import io.element.android.call.api.rtc.MatrixRtcLocalState
import io.element.android.call.api.rtc.MatrixRtcSignallingHealth
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcTile
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.MatrixRtcTileKind
import io.element.android.call.api.rtc.MatrixRtcUnableToDecrypt
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.impl.ElementCallStack
import io.element.android.call.test.ElementCallTestPattern
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

/** What the core reports at one moment of an outage fixture: its interruptions record, and the tiles that follow it. */
data class SampleOutage(
    val isSignallingUnreachable: Boolean = false,
    val isOwnMembershipLapsed: Boolean = false,
    val ownMedia: MatrixRtcConnectionState = MatrixRtcConnectionState.CONNECTED,
    val reconnecting: Set<String> = emptySet(),
    val missingKey: Set<String> = emptySet(),
    /** The core gives our own media server up: the call ends, connection lost. */
    val isEnded: Boolean = false,
)

/** The outage fixtures of feature-hq `harness/fixtures.md`. Times are from the call being up. */
enum class SampleOutageFixture(val key: String, val title: String, val description: String, val timeline: List<Pair<Duration, SampleOutage>>) {
    HOMESERVER_30S(
        key = "outage.homeserver.30s",
        title = "Homeserver away for 30 s",
        description = "The indicator and the server entry; the hand disabled while mic, camera and hang-up stay (034 R13, R15, R35).",
        timeline = listOf(5.seconds to SampleOutage(isSignallingUnreachable = true), 35.seconds to SampleOutage()),
    ),
    HOMESERVER_LAPSE(
        key = "outage.homeserver.lapse",
        title = "Homeserver away, membership lapsed",
        description = "The server entry saying the others dropped us, still after signalling returns until the republish (034 R17).",
        timeline = listOf(
            5.seconds to SampleOutage(isSignallingUnreachable = true),
            15.seconds to SampleOutage(isSignallingUnreachable = true, isOwnMembershipLapsed = true),
            45.seconds to SampleOutage(isOwnMembershipLapsed = true),
            48.seconds to SampleOutage(),
        ),
    ),
    OWN_SFU_RECOVERS(
        key = "outage.own_sfu.recovers_40s",
        title = "Our media server back after 40 s",
        description = "Our tile's spinner with its live preview kept, and starting a share disabled (034 R15, R23, R34).",
        timeline = listOf(5.seconds to SampleOutage(ownMedia = MatrixRtcConnectionState.RECONNECTING), 45.seconds to SampleOutage()),
    ),
    OWN_SFU_BUDGET(
        key = "outage.own_sfu.budget",
        title = "Our media server never back",
        description = "The call failing with its reason on screen, until it is closed (034 R27). The budget is shortened to 10 s.",
        timeline = listOf(5.seconds to SampleOutage(ownMedia = MatrixRtcConnectionState.RECONNECTING), 15.seconds to SampleOutage(isEnded = true)),
    ),
    PEER_SFU(
        key = "outage.peer_sfu.1m",
        title = "Carol and Bob's media server away for 1 min",
        description = "Two tiles as avatars with the name tag's spinner, and the others' media entry (034 R29, R34).",
        timeline = listOf(5.seconds to SampleOutage(reconnecting = setOf("Carol", "Bob")), 65.seconds to SampleOutage()),
    ),
    KEY_LATE(
        key = "outage.key.undelivered_then_delivered",
        title = "Erin's key late, then delivered",
        description = "Erin as an avatar with the spinner from 5 s to 12 s, and no indicator (034 R33, Q8).",
        timeline = listOf(5.seconds to SampleOutage(missingKey = setOf("Erin")), 12.seconds to SampleOutage()),
    ),
    KEY_MISSING(
        key = "outage.key.missing",
        title = "Erin's key never arrives",
        description = "From 15 s Erin's tile carries the key-off icon in critical red instead of the spinner, still no indicator (034 Q8).",
        timeline = listOf(5.seconds to SampleOutage(missingKey = setOf("Erin"))),
    ),
    ;

    companion object {
        fun fromKey(key: String): SampleOutageFixture? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One outage fixture through the real controller and the real screen, over a scripted core: a group call, connected from the
 * start, whose record and tiles change on the fixture's timeline. What is scripted is the core's output, not the outage.
 */
class SampleOutageCall(context: Context, private val scope: CoroutineScope, private val fixture: SampleOutageFixture) : SampleLiveCall {
    private val joined = CompletableDeferred<Unit>()
    private val client = FakeMatrixRtcClient(joinResult = { Result.success(call).also { joined.complete(Unit) } })
    private val call: FakeMatrixRtcCall = FakeMatrixRtcCall(ROOM_ID, "m.call#room").apply {
        status.value = MatrixRtcCallStatus.Connected(sinceMs = System.currentTimeMillis(), degraded = false)
        newMediaSession = { FakeMatrixRtcMediaSession(frames = ::pattern) }
    }
    private val stack = ElementCallStack.Builder(context, FakeMatrixBackend())
        .rtcClient(client)
        .roomContext(FakeElementCallRoomContextProvider(ElementCallRoomContext(displayName = "Outages", isDm = false, members = MEMBERS)))
        .build(scope)

    override val controller: ElementCallController get() = stack.controller

    override fun start() {
        controller.startCall(ElementCallData(roomId = ROOM_ID, isAudioCall = false, isStartingCall = true))
        scope.launch { play() }
    }

    override fun close() = stack.close()

    private suspend fun play() {
        joined.await()
        apply(SampleOutage())
        var elapsed = Duration.ZERO
        for ((at, outage) in fixture.timeline) {
            delay(at - elapsed)
            elapsed = at
            apply(outage)
        }
    }

    private fun apply(outage: SampleOutage) {
        val signalling = if (outage.isSignallingUnreachable) {
            MatrixRtcSignallingHealth.Unreachable(
                System.currentTimeMillis()
            )
        } else {
            MatrixRtcSignallingHealth.Reachable
        }
        client.signalling.value = signalling
        val session = call.lastMediaSession ?: return
        session.localState.value = MatrixRtcLocalState(tile(OWN, outage.ownMedia, unableToDecrypt = null), isScreenSharing = false)
        session.pushRoster(
            aRoster(
                *REMOTES.map { (name, hasVideo) ->
                    tile(
                        name = name,
                        connection = if (name in outage.reconnecting) MatrixRtcConnectionState.RECONNECTING else MatrixRtcConnectionState.CONNECTED,
                        unableToDecrypt = MatrixRtcUnableToDecrypt.MISSING_KEY.takeIf { name in outage.missingKey },
                        hasVideo = hasVideo,
                    )
                }.toTypedArray()
            )
        )
        session.interruptions.value = MatrixRtcInterruptions(
            signalling = signalling,
            isOwnMembershipLapsed = outage.isOwnMembershipLapsed,
            ownMedia = outage.ownMedia,
            membersReconnecting = outage.reconnecting.map(SampleFixture::memberIdOf),
            membersUnreachable = emptyList(),
            unableToDecrypt = outage.missingKey.map(SampleFixture::memberIdOf),
            unmappable = emptyList(),
        )
        if (outage.isEnded) {
            call.status.value = MatrixRtcCallStatus.Ended(MatrixRtcCallEnd.ConnectionClosed(MatrixRtcConnectionLossReason.BUDGET, "scripted"))
        }
    }

    @Suppress("UnusedParameter")
    private fun pattern(memberId: String, kind: MatrixRtcStreamKind): Flow<MatrixRtcVideoFrame> = ElementCallTestPattern.landscape().frames()

    private companion object {
        const val OWN = "Alice"
        val ROOM_ID = RoomId("!outages:example.com")

        /** `video`'s remote roster, so a tile that loses its media goes visibly from the pattern to an avatar. */
        val REMOTES = listOf("Carol" to true, "Bob" to true, "Dan" to false, "Erin" to true, "Frank" to true)
        val MEMBERS = (listOf(OWN) + REMOTES.map { it.first }).associate { name ->
            val userId = SampleFixture.userIdOf(name)
            userId to ElementCallRoomMember(userId = userId, displayName = name, avatarUrl = null)
        }

        fun tile(name: String, connection: MatrixRtcConnectionState, unableToDecrypt: MatrixRtcUnableToDecrypt?, hasVideo: Boolean = true) = MatrixRtcTile(
            id = MatrixRtcTileId(SampleFixture.memberIdOf(name), MatrixRtcTileKind.PERSON),
            userId = SampleFixture.userIdOf(name),
            deviceId = "DEVICE",
            isHero = false,
            hasVideo = hasVideo,
            isMicrophoneMuted = false,
            isSpeaking = false,
            handRaisedAtMs = null,
            handRaisedPosition = null,
            connection = connection,
            unableToDecrypt = unableToDecrypt,
        )
    }
}
