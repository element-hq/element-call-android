/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import android.os.SystemClock
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallRoomMember
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.rtc.MatrixRtcParticipant
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcStreamState
import io.element.android.call.api.rtc.id.UserId
import io.element.android.call.test.aSharingParticipant
import io.element.android.call.ui.AN_EARPIECE
import io.element.android.call.ui.A_BLUETOOTH_HEADSET
import io.element.android.call.ui.A_REMOTE_MEMBER_ID
import io.element.android.call.ui.A_SPEAKER
import io.element.android.call.ui.aCallSnapshot
import io.element.android.call.ui.aCrowdParticipant
import io.element.android.call.ui.aLocalParticipant
import io.element.android.call.ui.aRemoteCameraParticipant
import io.element.android.call.ui.aRemoteParticipant
import io.element.android.call.ui.aStaleParticipant
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap

/**
 * The arrangements the sample opens on: the same fixtures the previews and the screenshot tests use,
 * as running calls. Every `*PreviewParam` state that is a call is here (plan §11.1).
 *
 * [key] is what `--es fixture` on the launch intent takes, so an instrumented test starts where it means to;
 * [title] and [description] are what the picker shows.
 */
enum class SampleFixture(val key: String, val title: String, val description: String) {
    REQUESTING_PERMISSION(
        key = "requesting_permission",
        title = "Requesting the microphone",
        description = "Nothing happens until the permission is answered. The overlay asks, as the host's Activity would.",
    ) {
        override fun snapshot() = aCallSnapshot(connection = ElementCallConnection.RequestingPermission, isMaximized = true)
            .copy(isMicrophonePermissionGranted = false, roomMembers = ROOM_MEMBERS)
    },
    CONNECTING(
        key = "connecting",
        title = "Connecting",
        description = "Joined, media not yet up. The core already counts two members.",
    ) {
        override fun snapshot() = aConnected(connection = ElementCallConnection.ConnectingMedia, participants = emptyList()).copy(memberCount = 2)
    },
    ONE_TO_ONE(
        key = "one_to_one",
        title = "One to one",
        description = "A DM: the other person full bleed, us as a thumbnail.",
    ) {
        override fun snapshot() = aConnected(
            participants = listOf(aLocalParticipant(), aRemoteCameraParticipant()),
            isDm = true,
        )
    },
    GROUP(
        key = "group",
        title = "Group with a spotlight",
        description = "Three people, one talking. The strip excludes whoever is spotlighted.",
    ) {
        override fun snapshot() = aConnected(
            participants = listOf(aLocalParticipant(), aRemoteCameraParticipant(), aStaleParticipant()),
            speakingIds = setOf(A_REMOTE_MEMBER_ID),
        )
    },
    LARGE_CALL(
        key = "large_call",
        title = "Nine people",
        description = "A strip that pages. Every other member has a camera on.",
    ) {
        override fun snapshot() = aConnected(
            participants = listOf(aLocalParticipant()) + (1..CROWD_SIZE).map { index ->
                val member = aCrowdParticipant(index)
                if (index % 2 == 0) member.copy(streams = member.streams + MatrixRtcStreamState(MatrixRtcStreamKind.CAMERA, isMuted = false)) else member
            },
        ).copy(memberCount = CROWD_SIZE + 1)
    },
    SCREEN_SHARE(
        key = "screen_share",
        title = "Someone sharing their screen",
        description = "The screen takes the spotlight, its owner stays in the strip: two tiles, two streams.",
    ) {
        override fun snapshot() = aConnected(
            participants = listOf(aLocalParticipant(), aSharingParticipant(A_REMOTE_MEMBER_ID, userId = UserId("@bob:example.org")), aStaleParticipant()),
        )
    },
    MUTED(
        key = "muted",
        title = "Everyone muted",
        description = "Both microphones off, on a Bluetooth headset.",
    ) {
        override fun snapshot() = aConnected(
            participants = listOf(aLocalParticipant(isMuted = true), aRemoteParticipant(isMuted = true)),
            isDm = true,
        ).copy(isMicrophoneMuted = true, selectedAudioDevice = A_BLUETOOTH_HEADSET)
    },
    DEGRADED(
        key = "degraded",
        title = "Unstable connection",
        description = "Media is up but the transport reports trouble.",
    ) {
        override fun snapshot() = aConnected(
            connection = ElementCallConnection.Degraded,
            participants = listOf(aLocalParticipant(), aRemoteCameraParticipant()),
        )
    },
    FAILED(
        key = "failed",
        title = "Failed to join",
        description = "What a homeserver with no LiveKit transport looks like.",
    ) {
        override fun snapshot() = aCallSnapshot(connection = ElementCallConnection.Failed("Homeserver offers no LiveKit transport"), isMaximized = true)
            .copy(isMicrophonePermissionGranted = true, roomMembers = ROOM_MEMBERS)
    },
    MINIMIZED_BAR(
        key = "minimized_bar",
        title = "Minimized voice call",
        description = "Docked as a bar above the host's content. Tap it to come back.",
    ) {
        override fun snapshot() = aConnected(participants = listOf(aLocalParticipant(), aRemoteParticipant()), isDm = true).copy(isMaximized = false)
    },
    FLOATING_TILE(
        key = "floating_tile",
        title = "Minimized video call",
        description = "Floating as a draggable tile over the host's content. Drag it, then tap it.",
    ) {
        override fun snapshot() = aConnected(
            participants = listOf(aLocalParticipant(), aRemoteCameraParticipant()),
            isDm = true,
        ).copy(isMaximized = false)
    },
    ;

    /** Built when opened rather than once, so the duration counter starts from now. */
    abstract fun snapshot(): ElementCallSnapshot

    companion object {
        fun fromKey(key: String): SampleFixture? = entries.firstOrNull { it.key == key }

        private const val CROWD_SIZE = 8

        private val ROOM_MEMBERS = persistentMapOf(
            UserId("@alice:example.org") to ElementCallRoomMember(UserId("@alice:example.org"), displayName = "Alice", avatarUrl = null),
            UserId("@bob:example.org") to ElementCallRoomMember(UserId("@bob:example.org"), displayName = "Bob", avatarUrl = null),
        )

        private fun aConnected(
            connection: ElementCallConnection = ElementCallConnection.Connected,
            participants: List<MatrixRtcParticipant>,
            speakingIds: Set<String> = emptySet(),
            isDm: Boolean = false,
        ): ElementCallSnapshot = aCallSnapshot(
            connection = connection,
            connectedAtElapsedMs = SystemClock.elapsedRealtime(),
            isMaximized = true,
            participants = participants,
            speakingIds = speakingIds,
        ).copy(
            isDm = isDm,
            isMicrophonePermissionGranted = true,
            isCameraPermissionGranted = true,
            // The sample opts in, as its manifest does: it is where the share button is walked through by hand.
            isScreenShareAvailable = true,
            isCameraEnabled = participants.any { it.isLocal && it.streams.any { stream -> stream.kind == MatrixRtcStreamKind.CAMERA && !stream.isMuted } },
            memberCount = participants.size,
            roomMembers = (ROOM_MEMBERS +
                participants.associate {
                    it.userId to
                    ElementCallRoomMember(
                        it.userId,
                        displayName = it.userId.value.substringAfter('@').substringBefore(':').replaceFirstChar(Char::uppercase),
                        avatarUrl = null
                    )
                }).toImmutableMap(),
            audioDevices = persistentListOf(AN_EARPIECE, A_SPEAKER, A_BLUETOOTH_HEADSET),
            selectedAudioDevice = AN_EARPIECE,
        )
    }
}
