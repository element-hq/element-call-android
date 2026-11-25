/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallLobbyJoinLabel
import io.element.android.call.api.audio.CallAudioDevice
import io.element.android.call.api.rtc.id.UserId
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.flow.emptyFlow

/** The lobby's main states (feature-hq 045), for its previews and screenshots. */
open class LobbyElementCallScreenStatePreviewParam : PreviewParameterProvider<ElementCallScreenState> {
    override val values: Sequence<ElementCallScreenState>
        get() = sequenceOf(
            // Reading the call: the frame in place with our avatar, every control but settings and Close off (R59).
            aLobbyScreenState(lobby = ElementCallLobbyState.Loading, camera = ElementCallLobbyCamera.OFF),
            // Nobody in the call, the camera on.
            aLobbyScreenState(lobby = aReadyLobby(label = ElementCallLobbyJoinLabel.START, memberCount = 0)),
            // A call in progress, with its count (R41).
            aLobbyScreenState(lobby = aReadyLobby(label = ElementCallLobbyJoinLabel.JOIN, memberCount = 3)),
            // A voice call: our avatar in the same frame (R9), the earpiece.
            aLobbyScreenState(
                lobby = aReadyLobby(label = ElementCallLobbyJoinLabel.JOIN, memberCount = 2, camera = ElementCallLobbyCamera.OFF),
                camera = ElementCallLobbyCamera.OFF,
                selectedAudioDevice = AN_EARPIECE,
            ),
            // The call emptied while the lobby was open (R57).
            aLobbyScreenState(lobby = aReadyLobby(label = ElementCallLobbyJoinLabel.JOIN_ANYWAY, memberCount = 0, isCallEndedNoticeShown = true)),
            // The microphone refused, the system still asking, and no longer (R27).
            aLobbyScreenState(
                lobby = aReadyLobby(
                    label = ElementCallLobbyJoinLabel.JOIN,
                    memberCount = 1,
                    camera = ElementCallLobbyCamera.OFF,
                    joinUnavailableReason = ElementCallLobbyJoinBlock.MICROPHONE_REFUSED,
                    microphoneAction = ElementCallLobbyMicrophoneAction.ALLOW,
                ),
                camera = ElementCallLobbyCamera.OFF,
            ),
            aLobbyScreenState(
                lobby = aReadyLobby(
                    label = ElementCallLobbyJoinLabel.JOIN,
                    memberCount = 1,
                    camera = ElementCallLobbyCamera.OFF,
                    joinUnavailableReason = ElementCallLobbyJoinBlock.MICROPHONE_REFUSED,
                    microphoneAction = ElementCallLobbyMicrophoneAction.OPEN_SETTINGS,
                ),
                camera = ElementCallLobbyCamera.OFF,
            ),
            // The camera refused: the frame says why, Join stays on (R28).
            aLobbyScreenState(
                lobby = aReadyLobby(label = ElementCallLobbyJoinLabel.JOIN, memberCount = 2, camera = ElementCallLobbyCamera.NOT_ALLOWED),
                camera = ElementCallLobbyCamera.NOT_ALLOWED,
            ),
        )
}

fun aReadyLobby(
    label: ElementCallLobbyJoinLabel,
    memberCount: Int,
    camera: ElementCallLobbyCamera = ElementCallLobbyCamera.ON,
    isCallEndedNoticeShown: Boolean = false,
    joinUnavailableReason: ElementCallLobbyJoinBlock? = null,
    microphoneAction: ElementCallLobbyMicrophoneAction? = null,
) = ElementCallLobbyState.Ready(
    joinLabel = label,
    memberCount = memberCount,
    isCallEndedNoticeShown = isCallEndedNoticeShown,
    joinUnavailableReason = joinUnavailableReason,
    camera = camera,
    microphoneAction = microphoneAction,
)

/** The call screen in the lobby: our tile alone, as the presenter builds it before the core has reported us. */
fun aLobbyScreenState(
    lobby: ElementCallLobbyState,
    camera: ElementCallLobbyCamera = (lobby as? ElementCallLobbyState.Ready)?.camera ?: ElementCallLobbyCamera.OFF,
    selectedAudioDevice: CallAudioDevice? = A_SPEAKER,
): ElementCallScreenState {
    val isCameraOn = camera == ElementCallLobbyCamera.ON
    val ready = lobby as? ElementCallLobbyState.Ready
    val own = CallTileData(
        memberId = OWN_CAMERA_TILE_ID,
        userId = UserId("@alice:example.org"),
        roomMember = null,
        isLocal = true,
        isMuted = ready?.joinUnavailableReason == ElementCallLobbyJoinBlock.MICROPHONE_REFUSED,
        isActiveSpeaker = false,
        hasVideo = isCameraOn,
        isVideoMirrored = true,
        lobbyCamera = camera,
    )
    return anElementCallScreenState(
        connection = ElementCallConnection.Lobby(
            memberCount = ready?.memberCount,
            hasSeenMembers = ready != null && (ready.isCallEndedNoticeShown || ready.memberCount > 0),
        ),
        lobby = lobby,
        isCameraEnabled = isCameraOn,
        isCameraPermissionGranted = camera != ElementCallLobbyCamera.NOT_ALLOWED,
        isMicrophonePermissionGranted = ready?.joinUnavailableReason == null,
        selectedAudioDevice = selectedAudioDevice,
        audioDevices = listOfNotNull(A_BLUETOOTH_HEADSET.takeIf { selectedAudioDevice == A_BLUETOOTH_HEADSET }, AN_EARPIECE, A_SPEAKER),
        connectedAtElapsedMs = null,
    ).copy(
        tiles = persistentListOf(own),
        videoFrames = if (isCameraOn) persistentMapOf(OWN_CAMERA_TILE_ID to emptyFlow()) else persistentMapOf(),
        spotlight = CallSpotlight.Choice.None,
    )
}
