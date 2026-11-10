/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.element.android.call.api.ElementCallController
import io.element.android.call.api.ElementCallData
import io.element.android.call.api.ElementCallRoomContext
import io.element.android.call.api.ElementCallRoomMember
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

/**
 * The `permission_probe` fixture of feature-hq `harness/fixtures.md` (042): a video call with Bob on the real controller,
 * so the system's own permission dialogs appear and a refusal is handled as in a real call. Nothing is captured or sent.
 */
class SamplePermissionProbeCall(context: Context, private val scope: CoroutineScope) : SampleLiveCall {
    private val joined = CompletableDeferred<Unit>()
    private val client = FakeMatrixRtcClient(joinResult = { Result.success(call).also { joined.complete(Unit) } })
    private val call: FakeMatrixRtcCall = FakeMatrixRtcCall(ROOM_ID, "m.call#room").apply {
        status.value = MatrixRtcCallStatus.Connected(sinceMs = System.currentTimeMillis(), degraded = false)
        newMediaSession = { FakeMatrixRtcMediaSession(frames = ::pattern) }
    }
    private val stack = ElementCallStack.Builder(context, FakeMatrixBackend())
        .rtcClient(client)
        .roomContext(FakeElementCallRoomContextProvider(ElementCallRoomContext(displayName = TITLE, isDm = false, members = MEMBERS)))
        .preferencesStore(FakeElementCallPreferencesStore())
        .build(scope)

    override val controller: ElementCallController get() = stack.controller

    override fun start() {
        controller.startCall(ElementCallData(roomId = ROOM_ID, isAudioCall = false, isStartingCall = true))
        scope.launch { play() }
    }

    override fun close() = stack.close()

    private suspend fun play() {
        joined.await()
        while (call.lastMediaSession == null) delay(50)
        val session = call.lastMediaSession!!
        session.localState.value = MatrixRtcLocalState(tile(session.localMemberId, OWN), isScreenSharing = false)
        session.pushRoster(aRoster(tile(SampleFixture.memberIdOf(BOB), BOB)))
    }

    @Suppress("UnusedParameter")
    private fun pattern(memberId: String, kind: MatrixRtcStreamKind): Flow<MatrixRtcVideoFrame> = ElementCallTestPattern.landscape().frames()

    companion object {
        const val KEY = "permission_probe"
        const val TITLE = "Microphone and camera permissions"

        /** What the fixture's row says: Android can only tell allowed from not allowed without asking (fixtures.md). */
        fun describeAuthorization(context: Context): String {
            fun state(permission: String) =
                if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) "allowed" else "not allowed"
            return "Microphone: ${state(Manifest.permission.RECORD_AUDIO)} · Camera: ${state(Manifest.permission.CAMERA)}"
        }

        private const val OWN = "Alice"
        private const val BOB = "Bob"
        private val ROOM_ID = RoomId("!permissions:example.com")
        private val MEMBERS = listOf(OWN, BOB).associate { name ->
            val userId = SampleFixture.userIdOf(name)
            userId to ElementCallRoomMember(userId = userId, displayName = name, avatarUrl = null)
        }

        private fun tile(memberId: String, name: String) = MatrixRtcTile(
            id = MatrixRtcTileId(memberId, MatrixRtcTileKind.PERSON),
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
