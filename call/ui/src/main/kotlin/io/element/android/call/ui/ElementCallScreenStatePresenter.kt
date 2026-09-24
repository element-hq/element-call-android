/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallController
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.ElementCallVersion
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.flow.Flow

/**
 * Presents whatever call [controller] is running, as the state [ElementCallScreen] draws.
 *
 * Deliberately thin: the call itself lives in the controller, so this only shapes a snapshot into
 * screen state and forwards intent back. That split is what lets the same call be drawn by the
 * full-screen UI and by the minimized bar, and survive either of them going away. A Molecule-style
 * state producer without Element X's `Presenter` base: the composable *is* the presenter.
 */
@Composable
fun rememberElementCallScreenState(
    controller: ElementCallController,
    navigator: ElementCallNavigator,
): ElementCallScreenState {
    val snapshot by controller.state.collectAsState()

    // The controller starts a call asynchronously, so a null snapshot means "not yet" on the way
    // in and "over" on the way out. Only the second should close the screen, hence the latch:
    // closing on the first would race the call into existence and dismiss it before it began.
    var hasSeenCall by remember { mutableStateOf(false) }
    LaunchedEffect(snapshot != null) {
        if (snapshot != null) {
            hasSeenCall = true
        } else if (hasSeenCall) {
            navigator.close()
        }
    }

    val current = snapshot

    val tiles = current?.callTiles() ?: persistentListOf()

    // One stream per tile, by the tile's own member and kind: a sharer's camera and screen are two
    // streams, and keying by member would draw one of them into the other's tile. A tile without
    // video opens nothing, which is what makes a tile go to its avatar when a camera turns off.
    //
    // The flows come from the call and are stable per stream, so rebuilding this map on
    // recomposition does not disturb a tile that is already drawing.
    val videoFrames = tiles
        .filter { it.hasVideo }
        .associate { it.tileId to controller.videoFrames(it.memberId, it.streamKind) }
        .toImmutableMap()

    fun handleEvent(event: ElementCallScreenEvent) {
        when (event) {
            is ElementCallScreenEvent.SetMicrophonePermissionGranted -> controller.setMicrophonePermissionGranted(event.granted)
            is ElementCallScreenEvent.SetCameraPermissionGranted -> controller.setCameraPermissionGranted(event.granted)
            ElementCallScreenEvent.ToggleCamera -> {
                if (current?.isCameraPermissionGranted == true) {
                    controller.setCameraEnabled(!current.isCameraEnabled)
                } else {
                    // Asking is what resolves both "denied" and "never asked", and the controller
                    // turns the camera on itself once the answer comes back yes.
                    navigator.requestCameraPermission()
                }
            }
            ElementCallScreenEvent.SwitchCamera -> controller.switchCamera()
            ElementCallScreenEvent.ToggleTileStats -> controller.toggleTileStats()
            is ElementCallScreenEvent.SetVideoConstraints ->
                controller.setVideoConstraints(event.memberId, event.kind, event.constraints)
            is ElementCallScreenEvent.SetComposedTiles -> controller.setComposedTiles(event.tileIds)
            ElementCallScreenEvent.ToggleScreenShare -> {
                if (current?.isScreenSharing == true) {
                    controller.setScreenShareEnabled(token = null)
                } else {
                    // Always asks. Unlike the camera there is nothing to check first: the grant
                    // is spent when it is used, so every share starts at the system dialog.
                    navigator.requestScreenCapture()
                }
            }
            ElementCallScreenEvent.ToggleMicrophoneMuted -> controller.setMicrophoneMuted(current?.isMicrophoneMuted != true)
            ElementCallScreenEvent.ToggleAudioTestTone -> controller.setAudioTestToneEnabled(current?.isAudioTestToneEnabled != true)
            is ElementCallScreenEvent.SelectAudioDevice -> controller.selectAudioDevice(event.device)
            ElementCallScreenEvent.Minimize -> controller.setMaximized(false)
            ElementCallScreenEvent.HangUp -> controller.hangUp()
        }
    }

    return current.toState(videoFrames = videoFrames, tiles = tiles, eventSink = ::handleEvent)
}

/**
 * Our own tile first, then the core's ranking untouched.
 *
 * First because the self view has to go somewhere and the core has no opinion: last would put us on
 * the final page of a big call, and first is where iOS puts it. Our mute and camera come from the
 * call rather than from the core's tile, so a tap shows on the badge before the round trip does.
 */
private fun ElementCallSnapshot.callTiles(): ImmutableList<CallTileData> {
    val own = ownTile?.let {
        it.copy(isHero = false, isMicrophoneMuted = isMicrophoneMuted, hasVideo = isCameraEnabled)
            .toCallTileData(roomMembers, isLocal = true, isFrontCamera = isFrontCamera)
    }
    val ranked = tiles.map { it.toCallTileData(roomMembers, isLocal = false, isFrontCamera = isFrontCamera) }
    return (listOfNotNull(own) + ranked).toImmutableList()
}

/**
 * Screen state for a call, or for the moment before one exists.
 *
 * A null snapshot reads as [ElementCallConnection.RequestingPermission] because that is what comes
 * next: the controller has been asked for a call and the microphone is the first thing it needs.
 */
private fun ElementCallSnapshot?.toState(
    videoFrames: ImmutableMap<String, Flow<MatrixRtcVideoFrame>>,
    tiles: ImmutableList<CallTileData>,
    eventSink: (ElementCallScreenEvent) -> Unit,
) = ElementCallScreenState(
    connection = this?.connection ?: ElementCallConnection.RequestingPermission,
    memberCount = this?.memberCount ?: 0,
    participants = this?.participants ?: persistentListOf(),
    audioLevels = this?.audioLevels ?: persistentMapOf(),
    receiveStats = this?.receiveStats ?: persistentMapOf(),
    frameEncryption = this?.frameEncryption ?: persistentMapOf(),
    isMicrophoneMuted = this?.isMicrophoneMuted == true,
    isAudioTestToneEnabled = this?.isAudioTestToneEnabled == true,
    audioDevices = this?.audioDevices ?: persistentListOf(),
    selectedAudioDevice = this?.selectedAudioDevice,
    isMicrophonePermissionGranted = this?.isMicrophonePermissionGranted == true,
    isCameraEnabled = this?.isCameraEnabled == true,
    isFrontCamera = this?.isFrontCamera != false,
    isCameraPermissionGranted = this?.isCameraPermissionGranted == true,
    isScreenShareAvailable = this?.isScreenShareAvailable == true,
    isScreenSharing = this?.isScreenSharing == true,
    isTileStatsVisible = this?.isTileStatsVisible == true,
    videoFrames = videoFrames,
    roomName = this?.roomName,
    isDm = this?.isDm == true,
    connectedAtElapsedMs = this?.connectedAtElapsedMs,
    tiles = tiles,
    spotlightTileId = this?.spotlightTileId?.let { id -> tiles.firstOrNull { it.id == id }?.tileId },
    libraryVersion = ElementCallVersion.library,
    coreVersion = ElementCallVersion.core,
    eventSink = eventSink,
)
