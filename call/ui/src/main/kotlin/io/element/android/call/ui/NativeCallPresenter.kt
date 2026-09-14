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
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.call.impl.NativeCallConnection
import io.element.android.call.impl.NativeCallController
import io.element.android.call.impl.NativeCallSnapshot
import io.element.android.libraries.architecture.Presenter
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.flow.Flow

/**
 * Presents whatever call [NativeCallController] is running.
 *
 * Deliberately thin: the call itself lives in the controller, so this only shapes a snapshot into
 * screen state and forwards intent back. That split is what lets the same call be drawn by the
 * full-screen UI and by the minimized bar, and survive either of them going away.
 */
@AssistedInject
class NativeCallPresenter(
    @Assisted private val navigator: NativeCallNavigator,
    private val controller: NativeCallController,
) : Presenter<NativeCallState> {
    @AssistedFactory
    interface Factory {
        fun create(navigator: NativeCallNavigator): NativeCallPresenter
    }

    @Composable
    override fun present(): NativeCallState {
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

        // Derived from the media roster rather than tracked separately, and it treats us like anyone
        // else: publishing raises a stream against our own member id, so our camera appears here by
        // the same route a peer's does. Muted streams are excluded, which is what makes a tile vanish
        // when someone turns their camera off - ours included, since disabling mutes the track.
        //
        // The flows come from the call and are stable per member, so rebuilding this map on
        // recomposition does not disturb a tile that is already drawing.
        // Keyed by tile rather than by member, because a member sharing their screen has two of them.
        val videoFrames = if (current == null) {
            persistentMapOf()
        } else {
            buildMap {
                current.participants.forEach { participant ->
                    if (participant.publishes(MatrixRtcStreamKind.CAMERA)) {
                        put(participant.memberId, controller.videoFrames(participant.memberId, MatrixRtcStreamKind.CAMERA))
                    }
                    // Ours is published but never drawn - see toCallTiles - so there is no stream to
                    // open for it either.
                    if (!participant.isLocal && participant.publishes(MatrixRtcStreamKind.SCREEN_SHARE)) {
                        put(
                            screenShareTileId(participant.memberId),
                            controller.videoFrames(participant.memberId, MatrixRtcStreamKind.SCREEN_SHARE),
                        )
                    }
                }
            }.toImmutableMap()
        }

        fun handleEvent(event: NativeCallEvent) {
            when (event) {
                is NativeCallEvent.SetMicrophonePermissionGranted -> controller.setMicrophonePermissionGranted(event.granted)
                is NativeCallEvent.SetCameraPermissionGranted -> controller.setCameraPermissionGranted(event.granted)
                NativeCallEvent.ToggleCamera -> {
                    if (current?.isCameraPermissionGranted == true) {
                        controller.setCameraEnabled(!current.isCameraEnabled)
                    } else {
                        // Asking is what resolves both "denied" and "never asked", and the controller
                        // turns the camera on itself once the answer comes back yes.
                        navigator.requestCameraPermission()
                    }
                }
                NativeCallEvent.SwitchCamera -> controller.switchCamera()
                NativeCallEvent.ToggleTileStats -> controller.toggleTileStats()
                is NativeCallEvent.SetVideoConstraints ->
                    controller.setVideoConstraints(event.memberId, event.kind, event.constraints)
                NativeCallEvent.ToggleScreenShare -> {
                    if (current?.isScreenSharing == true) {
                        controller.setScreenShareEnabled(token = null)
                    } else {
                        // Always asks. Unlike the camera there is nothing to check first: the grant
                        // is spent when it is used, so every share starts at the system dialog.
                        navigator.requestScreenCapture()
                    }
                }
                NativeCallEvent.ToggleMicrophoneMuted -> controller.setMicrophoneMuted(current?.isMicrophoneMuted != true)
                NativeCallEvent.ToggleAudioTestTone -> controller.setAudioTestToneEnabled(current?.isAudioTestToneEnabled != true)
                is NativeCallEvent.SelectAudioDevice -> controller.selectAudioDevice(event.device)
                NativeCallEvent.Minimize -> controller.setMaximized(false)
                NativeCallEvent.HangUp -> controller.hangUp()
            }
        }

        val tiles = current?.participants
            ?.flatMap {
                it.toCallTiles(
                    roomMembers = current.roomMembers,
                    activeSpeakerIds = current.activeSpeakerIds,
                    isFrontCamera = current.isFrontCamera,
                )
            }
            ?.toImmutableList()
            ?: persistentListOf()

        return current.toState(videoFrames = videoFrames, tiles = tiles, eventSink = ::handleEvent)
    }
}

/**
 * Screen state for a call, or for the moment before one exists.
 *
 * A null snapshot reads as [NativeCallConnection.RequestingPermission] because that is what comes
 * next: the controller has been asked for a call and the microphone is the first thing it needs.
 */
private fun NativeCallSnapshot?.toState(
    videoFrames: ImmutableMap<String, Flow<MatrixRtcVideoFrame>>,
    tiles: ImmutableList<CallParticipant>,
    eventSink: (NativeCallEvent) -> Unit,
) = NativeCallState(
    connection = this?.connection ?: NativeCallConnection.RequestingPermission,
    memberCount = this?.memberCount ?: 0,
    participants = this?.participants ?: persistentListOf(),
    audioLevels = this?.audioLevels ?: persistentMapOf(),
    receiveStats = this?.receiveStats ?: persistentMapOf(),
    frameEncryption = this?.frameEncryption ?: persistentMapOf(),
    activeSpeakerIds = this?.activeSpeakerIds ?: persistentSetOf(),
    isMicrophoneMuted = this?.isMicrophoneMuted == true,
    isAudioTestToneEnabled = this?.isAudioTestToneEnabled == true,
    audioDevices = this?.audioDevices ?: persistentListOf(),
    selectedAudioDevice = this?.selectedAudioDevice,
    isMicrophonePermissionGranted = this?.isMicrophonePermissionGranted == true,
    isCameraEnabled = this?.isCameraEnabled == true,
    isFrontCamera = this?.isFrontCamera != false,
    isCameraPermissionGranted = this?.isCameraPermissionGranted == true,
    isScreenSharing = this?.isScreenSharing == true,
    isTileStatsVisible = this?.isTileStatsVisible == true,
    videoFrames = videoFrames,
    roomName = this?.roomName,
    isDm = this?.isDm == true,
    connectedAtElapsedMs = this?.connectedAtElapsedMs,
    tiles = tiles,
    spotlightMemberId = this?.spotlightMemberId,
    eventSink = eventSink,
)
