/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.audio.CallAudioDevice
import io.element.android.call.api.audio.CallAudioDeviceType
import io.element.android.call.api.rtc.MatrixRtcAudioLevel
import io.element.android.call.api.rtc.MatrixRtcFrameEncryptionState
import io.element.android.call.api.rtc.MatrixRtcParticipant
import io.element.android.call.api.rtc.MatrixRtcReceiveStats
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcStreamState
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import io.element.android.call.api.rtc.id.UserId
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

open class ElementCallScreenStatePreviewParam : PreviewParameterProvider<ElementCallScreenState> {
    override val values: Sequence<ElementCallScreenState>
        get() = sequenceOf(
            anElementCallScreenState(connection = ElementCallConnection.RequestingPermission),
            anElementCallScreenState(connection = ElementCallConnection.Joining),
            // Joined and counting members, no media yet.
            anElementCallScreenState(connection = ElementCallConnection.ConnectingMedia, memberCount = 2),
            anElementCallScreenState(
                connection = ElementCallConnection.Connected,
                memberCount = 2,
                participants = listOf(aLocalParticipant(), aRemoteParticipant()),
                audioLevels = mapOf(
                    A_LOCAL_MEMBER_ID to MatrixRtcAudioLevel(level = 0.7f, frameCount = 1_200),
                    A_REMOTE_MEMBER_ID to MatrixRtcAudioLevel(level = 0.3f, frameCount = 1_180),
                ),
                frameEncryption = mapOf(
                    A_LOCAL_MEMBER_ID to MatrixRtcFrameEncryptionState.OK,
                    A_REMOTE_MEMBER_ID to MatrixRtcFrameEncryptionState.OK,
                ),
                activeSpeakerIds = setOf(A_LOCAL_MEMBER_ID),
            ),
            anElementCallScreenState(
                connection = ElementCallConnection.Connected,
                memberCount = 2,
                participants = listOf(aLocalParticipant(isMuted = true), aRemoteParticipant()),
                isMicrophoneMuted = true,
            ),
            // Joined and fed a membership, but the transport has not reported anyone yet: the count
            // leads the participant list, which is the usual order on a call we have just joined.
            anElementCallScreenState(
                connection = ElementCallConnection.Connected,
                memberCount = 2,
                participants = emptyList(),
            ),
            // Media is up and the SFU hears the other side, but nothing decodes for us: packets are
            // arriving, almost all of the audio played is invented, and the key is missing.
            anElementCallScreenState(
                connection = ElementCallConnection.Connected,
                memberCount = 2,
                participants = listOf(aLocalParticipant(), aRemoteParticipant()),
                audioLevels = mapOf(
                    A_LOCAL_MEMBER_ID to MatrixRtcAudioLevel(level = 0.6f, frameCount = 900),
                    A_REMOTE_MEMBER_ID to MatrixRtcAudioLevel(level = 0f, frameCount = 900),
                ),
                receiveStats = mapOf(A_REMOTE_MEMBER_ID to aReceiveStats(concealedSamples = 42_000)),
                frameEncryption = mapOf(
                    A_LOCAL_MEMBER_ID to MatrixRtcFrameEncryptionState.OK,
                    A_REMOTE_MEMBER_ID to MatrixRtcFrameEncryptionState.MISSING_KEY,
                ),
                activeSpeakerIds = setOf(A_REMOTE_MEMBER_ID),
            ),
            // Three memberships in a two-party call: the third publishes nothing and the core has never
            // reported an encryption state for it. The observed shape of a leave that never landed.
            anElementCallScreenState(
                connection = ElementCallConnection.Connected,
                memberCount = 3,
                participants = listOf(aLocalParticipant(), aRemoteParticipant(), aStaleParticipant()),
                audioLevels = mapOf(
                    A_LOCAL_MEMBER_ID to MatrixRtcAudioLevel(level = 0.5f, frameCount = 3_000),
                    A_REMOTE_MEMBER_ID to MatrixRtcAudioLevel(level = 0f, frameCount = 3_000),
                ),
                receiveStats = mapOf(A_REMOTE_MEMBER_ID to aReceiveStats(packetsReceived = 2_400)),
                frameEncryption = mapOf(
                    A_LOCAL_MEMBER_ID to MatrixRtcFrameEncryptionState.MISSING_KEY,
                    A_REMOTE_MEMBER_ID to MatrixRtcFrameEncryptionState.MISSING_KEY,
                ),
            ),
            // Our camera on, the other side audio only. The tile is a placeholder in a preview, but
            // its box is what shows whether the card still reads properly with a picture in it.
            anElementCallScreenState(
                connection = ElementCallConnection.Connected,
                memberCount = 2,
                participants = listOf(aLocalParticipant(), aRemoteParticipant()),
                isCameraEnabled = true,
                isCameraPermissionGranted = true,
                videoFrames = mapOf(A_LOCAL_MEMBER_ID to emptyFlow()),
            ),
            // Both sides on video, which is the case the list layout has to survive: two tiles plus
            // two cards' worth of diagnostics is what pushes the hang-up button off a short screen.
            anElementCallScreenState(
                connection = ElementCallConnection.Connected,
                memberCount = 2,
                participants = listOf(aLocalParticipant(), aRemoteParticipant()),
                isCameraEnabled = true,
                isCameraPermissionGranted = true,
                videoFrames = mapOf(
                    A_LOCAL_MEMBER_ID to emptyFlow(),
                    A_REMOTE_MEMBER_ID to emptyFlow(),
                ),
            ),
            // Camera permission granted but the camera off, which is the state the controls row has
            // to distinguish from "never asked".
            anElementCallScreenState(
                connection = ElementCallConnection.Connected,
                memberCount = 2,
                participants = listOf(aLocalParticipant(), aRemoteParticipant()),
                isCameraPermissionGranted = true,
            ),
            // Publishing the test tone instead of the microphone.
            anElementCallScreenState(
                connection = ElementCallConnection.Connected,
                memberCount = 2,
                participants = listOf(aLocalParticipant(), aRemoteParticipant()),
                audioLevels = mapOf(A_LOCAL_MEMBER_ID to MatrixRtcAudioLevel(level = 0.8f, frameCount = 400)),
                isAudioTestToneEnabled = true,
            ),
            // A host that has turned screen sharing on: the sixth button, which is what the bar is sized for.
            anElementCallScreenState(
                connection = ElementCallConnection.Connected,
                memberCount = 2,
                participants = listOf(aLocalParticipant(), aRemoteParticipant()),
                isScreenShareAvailable = true,
            ),
            // Sharing our screen: the active button and the banner with its stop action. No tile of our
            // own share, see ScreenShareBanner.
            anElementCallScreenState(
                connection = ElementCallConnection.Connected,
                memberCount = 2,
                participants = listOf(aLocalParticipant(), aRemoteParticipant()),
                isScreenShareAvailable = true,
                isScreenSharing = true,
            ),
            // On the loudspeaker.
            anElementCallScreenState(
                connection = ElementCallConnection.Connected,
                memberCount = 2,
                participants = listOf(aLocalParticipant(), aRemoteParticipant()),
                selectedAudioDevice = A_SPEAKER,
            ),
            // On a headset that was paired mid-call, which is what the picker exists for.
            anElementCallScreenState(
                connection = ElementCallConnection.Connected,
                memberCount = 2,
                participants = listOf(aLocalParticipant(), aRemoteParticipant()),
                audioDevices = listOf(A_BLUETOOTH_HEADSET, AN_EARPIECE, A_SPEAKER),
                selectedAudioDevice = A_BLUETOOTH_HEADSET,
            ),
            anElementCallScreenState(
                connection = ElementCallConnection.Degraded,
                memberCount = 2,
                participants = listOf(aLocalParticipant(), aRemoteParticipant(isReachable = false)),
            ),
            anElementCallScreenState(connection = ElementCallConnection.Failed("Homeserver offers no LiveKit transport")),
            anElementCallScreenState(connection = ElementCallConnection.Ended),
            // A DM, both on video: the other person full-bleed, us as a thumbnail with the switch
            // camera button on it, the duration over the top, and no switch camera in the bar.
            anElementCallScreenState(
                connection = ElementCallConnection.Connected,
                memberCount = 2,
                participants = listOf(aLocalParticipant(), aRemoteParticipant()),
                isDm = true,
                isCameraEnabled = true,
                isCameraPermissionGranted = true,
                videoFrames = mapOf(
                    A_LOCAL_MEMBER_ID to emptyFlow(),
                    A_REMOTE_MEMBER_ID to emptyFlow(),
                ),
            ),
            // A DM where the other side has no camera: their avatar fills the area instead.
            anElementCallScreenState(
                connection = ElementCallConnection.Connected,
                memberCount = 2,
                participants = listOf(aLocalParticipant(), aRemoteParticipant()),
                isDm = true,
                isCameraEnabled = true,
                isCameraPermissionGranted = true,
                videoFrames = mapOf(A_LOCAL_MEMBER_ID to emptyFlow()),
            ),
            // A DM with our camera off and the other side muted: the thumbnail keeps its place as an
            // avatar with no switch button, and the mute badge is the only chrome on the big tile.
            anElementCallScreenState(
                connection = ElementCallConnection.Connected,
                memberCount = 2,
                participants = listOf(aLocalParticipant(), aRemoteParticipant(isMuted = true)),
                isDm = true,
                isCameraPermissionGranted = true,
                videoFrames = mapOf(A_REMOTE_MEMBER_ID to emptyFlow()),
            ),
            // A DM that a third person has joined: back to the group layout, whatever the room says.
            anElementCallScreenState(
                connection = ElementCallConnection.Connected,
                memberCount = 3,
                participants = listOf(aLocalParticipant(), aRemoteParticipant(), aStaleParticipant()),
                isDm = true,
            ),
            // Twelve people: past what the grid can hold legibly, so the strip is a scrolling row of
            // fixed-size tiles and the spotlight has the rest. Only the first few strip tiles fit.
            anElementCallScreenState(
                connection = ElementCallConnection.Connected,
                memberCount = 12,
                participants = listOf(aLocalParticipant(), aRemoteParticipant()) + (1..10).map { aCrowdParticipant(it) },
                activeSpeakerIds = setOf(A_REMOTE_MEMBER_ID),
                videoFrames = mapOf(
                    A_REMOTE_MEMBER_ID to emptyFlow(),
                    aCrowdMemberId(1) to emptyFlow(),
                    aCrowdMemberId(2) to emptyFlow(),
                ),
            ),
            // Fifty people. The screen should look no different from twelve - the same first page,
            // more dots - because everything past the next page is not composed at all.
            anElementCallScreenState(
                connection = ElementCallConnection.Connected,
                memberCount = 50,
                participants = listOf(aLocalParticipant(), aRemoteParticipant()) + (1..48).map { aCrowdParticipant(it) },
                activeSpeakerIds = setOf(A_REMOTE_MEMBER_ID),
                videoFrames = mapOf(A_REMOTE_MEMBER_ID to emptyFlow()),
            ),
        )
}

fun anElementCallScreenState(
    connection: ElementCallConnection = ElementCallConnection.Connected,
    memberCount: Int = 0,
    participants: List<MatrixRtcParticipant> = emptyList(),
    audioLevels: Map<String, MatrixRtcAudioLevel> = emptyMap(),
    receiveStats: Map<String, MatrixRtcReceiveStats> = emptyMap(),
    frameEncryption: Map<String, MatrixRtcFrameEncryptionState> = emptyMap(),
    activeSpeakerIds: Set<String> = emptySet(),
    isMicrophoneMuted: Boolean = false,
    isAudioTestToneEnabled: Boolean = false,
    audioDevices: List<CallAudioDevice> = listOf(AN_EARPIECE, A_SPEAKER),
    selectedAudioDevice: CallAudioDevice? = AN_EARPIECE,
    isMicrophonePermissionGranted: Boolean = true,
    isCameraEnabled: Boolean = false,
    isFrontCamera: Boolean = true,
    isCameraPermissionGranted: Boolean = false,
    // Off, like the library's default, so the bulk of the previews show what a host gets out of the box.
    isScreenShareAvailable: Boolean = false,
    isScreenSharing: Boolean = false,
    isTileStatsVisible: Boolean = false,
    // Never a real stream in a preview: the tile draws a placeholder under LocalInspectionMode and
    // touches no GL, so a flow here would only be collected and ignored. What matters for a preview
    // is which members have an entry, since that is what decides who gets a tile.
    videoFrames: Map<String, Flow<MatrixRtcVideoFrame>> = emptyMap(),
    roomName: String? = "Paulina",
    isDm: Boolean = false,
    // Zero rather than null so a connected preview shows a duration; the label reads a fixed value
    // under inspection anyway.
    connectedAtElapsedMs: Long? = 0L,
    // Fixed rather than the stamped ones, so the screenshots do not change at every release.
    libraryVersion: String = "0.4.0",
    coreVersion: String = "0.3.0",
    eventSink: (ElementCallScreenEvent) -> Unit = {},
) = ElementCallScreenState(
    connection = connection,
    memberCount = memberCount,
    participants = participants.toImmutableList(),
    audioLevels = audioLevels.toImmutableMap(),
    receiveStats = receiveStats.toImmutableMap(),
    frameEncryption = frameEncryption.toImmutableMap(),
    isMicrophoneMuted = isMicrophoneMuted,
    isAudioTestToneEnabled = isAudioTestToneEnabled,
    audioDevices = audioDevices.toImmutableList(),
    selectedAudioDevice = selectedAudioDevice,
    isMicrophonePermissionGranted = isMicrophonePermissionGranted,
    isCameraEnabled = isCameraEnabled,
    isFrontCamera = isFrontCamera,
    isCameraPermissionGranted = isCameraPermissionGranted,
    isScreenShareAvailable = isScreenShareAvailable,
    isScreenSharing = isScreenSharing,
    isTileStatsVisible = isTileStatsVisible,
    videoFrames = videoFrames.toImmutableMap(),
    roomName = roomName,
    isDm = isDm,
    connectedAtElapsedMs = connectedAtElapsedMs,
    // Derived rather than passed, so a preview cannot describe a call whose tiles disagree with its
    // participants - which is exactly the sort of state the real presenter can never produce.
    tiles = previewCallTiles(participants, activeSpeakerIds, isFrontCamera),
    // The head of the ranking, which is what the controller spotlights.
    spotlightTileId = previewCallTiles(participants, activeSpeakerIds, isFrontCamera).firstOrNull { !it.isLocal }?.tileId,
    libraryVersion = libraryVersion,
    coreVersion = coreVersion,
    eventSink = eventSink,
)

fun aReceiveStats(
    packetsReceived: Long = 1_500,
    concealedSamples: Long = 0,
    totalSamplesReceived: Long = 48_000,
) = MatrixRtcReceiveStats(
    packetsReceived = packetsReceived,
    packetsLost = 0,
    bytesReceived = packetsReceived * 120,
    jitter = 0.004,
    framesDecoded = 0,
    framesDropped = 0,
    totalSamplesReceived = totalSamplesReceived,
    concealedSamples = concealedSamples,
    silentConcealedSamples = concealedSamples,
    concealmentEvents = if (concealedSamples > 0) 1 else 0,
)

// Real member ids are 32 hex characters and the card shows a truncated prefix, so the fixtures use
// the same shape: a preview with a short readable id would not show whether the truncation is legible.
val AN_EARPIECE = CallAudioDevice(id = 1, type = CallAudioDeviceType.EARPIECE, productName = null)
val A_SPEAKER = CallAudioDevice(id = 2, type = CallAudioDeviceType.SPEAKER, productName = null)
val A_BLUETOOTH_HEADSET = CallAudioDevice(id = 3, type = CallAudioDeviceType.BLUETOOTH, productName = "WH-1000XM4")

const val A_LOCAL_MEMBER_ID = "6ffd927ac275815176463d6fee57ed62"
const val A_REMOTE_MEMBER_ID = "2921a8e353fbc938be76f5b2f4946178"
const val A_STALE_MEMBER_ID = "867ea783fbb6c0e3356856a6a5db65e4"

fun aLocalParticipant(isMuted: Boolean = false) = MatrixRtcParticipant(
    memberId = A_LOCAL_MEMBER_ID,
    userId = UserId("@alice:example.org"),
    deviceId = "ALICEDEVICE",
    isLocal = true,
    isReachable = true,
    streams = listOf(MatrixRtcStreamState(MatrixRtcStreamKind.MICROPHONE, isMuted)),
)

fun aRemoteParticipant(isReachable: Boolean = true, isMuted: Boolean = false) = MatrixRtcParticipant(
    memberId = A_REMOTE_MEMBER_ID,
    userId = UserId("@bob:example.org"),
    deviceId = "BOBDEVICE",
    isLocal = false,
    isReachable = isReachable,
    streams = persistentListOf(MatrixRtcStreamState(MatrixRtcStreamKind.MICROPHONE, isMuted = isMuted)),
)

/** The remote member with their camera on: what gives the floating tile and picture-in-picture something to draw. */
fun aRemoteCameraParticipant() = aRemoteParticipant().copy(
    streams = persistentListOf(
        MatrixRtcStreamState(MatrixRtcStreamKind.MICROPHONE, isMuted = false),
        MatrixRtcStreamState(MatrixRtcStreamKind.CAMERA, isMuted = false),
    ),
)

/** One of a crowd: distinct id and name, microphone only, every third one muted. */
fun aCrowdParticipant(index: Int) = MatrixRtcParticipant(
    memberId = aCrowdMemberId(index),
    userId = UserId("@member$index:example.org"),
    deviceId = "DEVICE$index",
    isLocal = false,
    isReachable = true,
    streams = listOf(MatrixRtcStreamState(MatrixRtcStreamKind.MICROPHONE, isMuted = index % 3 == 0)),
)

/** A 32-hex-character member id like the real ones, distinct per [index]. */
fun aCrowdMemberId(index: Int): String = "%032x".format(0xC0FFEE00L + index)

/**
 * A membership the core still counts but that publishes nothing: no streams, no device, no stats.
 *
 * These show up whenever a leave fails to land, and the point of the preview is that the card makes it
 * obvious the empty numbers are this member's and not the neighbouring one's.
 */
fun aStaleParticipant() = MatrixRtcParticipant(
    memberId = A_STALE_MEMBER_ID,
    userId = UserId("@carol:example.org"),
    deviceId = null,
    isLocal = false,
    isReachable = true,
    streams = emptyList(),
)

/** Our own tile first, then the core's shape of the rest: what the presenter builds from a snapshot. */
private fun previewCallTiles(
    participants: List<MatrixRtcParticipant>,
    speakingIds: Set<String>,
    isFrontCamera: Boolean,
) = (listOfNotNull(participants.previewOwnTile()?.let { it.copy(isSpeaking = it.id.memberId in speakingIds) }) + participants.previewTiles(speakingIds))
    .map { tile ->
        val participant = participants.first { it.memberId == tile.id.memberId }
        tile.toCallParticipant(
            roomMembers = emptyMap(),
            isLocal = participant.isLocal,
            hasMicrophone = participant.hasStream(MatrixRtcStreamKind.MICROPHONE),
            isFrontCamera = isFrontCamera,
        )
    }
    .toImmutableList()
