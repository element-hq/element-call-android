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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

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
    spotlightMemory: CallSpotlightMemory = rememberCallSpotlightMemory(),
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

    // The spotlight is a way of looking, so it is chosen here rather than by the controller, which
    // runs with no UI; what it needs to remember lives above the screen (spec 003 R7, R20, R67).
    val spotlight = CallSpotlight.choose(tiles, spotlightMemory.shownHeroId, spotlightMemory.lastSpeakerId, spotlightMemory.lastHeroes)
    val heroes = tiles.filter { it.isHero && !it.isLocal }.map { it.tileId }

    // Fullscreen is the screen's own state (spec 000 R3): saved across rotation (R10), gone with the
    // screen when the call is minimised (R25), and ended when the tile leaves (R19).
    var fullscreenTileId by rememberSaveable { mutableStateOf<String?>(null) }
    var isFullscreenChromeVisible by rememberSaveable { mutableStateOf(false) }
    // The stage's chrome (spec 014). Not saved: a rotation resets it (R12), and minimizing or Picture
    // in Picture disposes this presenter, so coming back starts from the orientation's start (R29).
    var stageChrome by remember { mutableStateOf(ElementCallChromeVisibility.Initial) }
    val chromeScope = rememberCoroutineScope()
    val pendingTap = remember { JobRef() }
    val pendingReturn = remember { JobRef() }
    fun applyStageChrome(event: ElementCallChromeVisibility.Event) {
        stageChrome = stageChrome.apply(event)
    }
    LaunchedEffect(tiles) {
        if (fullscreenTileId != null && tiles.none { it.tileId == fullscreenTileId }) {
            fullscreenTileId = null
            isFullscreenChromeVisible = false
            applyStageChrome(ElementCallChromeVisibility.Event.FullscreenEnded(byDeparture = true))
        }
    }
    LaunchedEffect(spotlight, heroes) {
        when (spotlight) {
            is CallSpotlight.Choice.Hero -> {
                spotlightMemory.shownHeroId = spotlight.tileId
                // Cleared with a hero, and below the threshold: the held speaker is listen mode's (R9).
                spotlightMemory.lastSpeakerId = null
            }
            is CallSpotlight.Choice.Speaker -> spotlightMemory.lastSpeakerId = spotlight.tileId
            CallSpotlight.Choice.None -> spotlightMemory.lastSpeakerId = null
        }
        spotlightMemory.lastHeroes = heroes
        // A small call's window follows its speaker, chosen above the screen (019 R15).
        if (!SmallCallLayout.applies(tiles)) {
            spotlightMemory.spotlightId = spotlight.tileId?.let { id -> tiles.firstOrNull { it.tileId == id }?.id }
        }
    }

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
            is ElementCallScreenEvent.SetDetailWindow -> controller.setDetailWindow(event.window)
            is ElementCallScreenEvent.ShowHero -> {
                if (event.tileId in heroes) spotlightMemory.shownHeroId = event.tileId
            }
            is ElementCallScreenEvent.ToggleFullscreen -> {
                // The tap that began this double tap toggles nothing (014 R16).
                pendingTap.cancel()
                val isLeaving = fullscreenTileId == event.tileId
                fullscreenTileId = if (isLeaving) null else event.tileId
                // Hidden on entry (000 R8), and nothing to show on the way out.
                isFullscreenChromeVisible = false
                if (isLeaving) applyStageChrome(ElementCallChromeVisibility.Event.FullscreenEnded(byDeparture = false))
            }
            ElementCallScreenEvent.ExitFullscreen -> {
                fullscreenTileId = null
                isFullscreenChromeVisible = false
                applyStageChrome(ElementCallChromeVisibility.Event.FullscreenEnded(byDeparture = false))
            }
            StageChromeEvent.TapStage -> {
                pendingReturn.cancel()
                // A second tap inside the wait is the other half of a double tap.
                if (pendingTap.isActive) {
                    pendingTap.cancel()
                } else {
                    pendingTap.job = chromeScope.launch {
                        delay(CHROME_TAP_DELAY_MS)
                        if (fullscreenTileId != null) {
                            isFullscreenChromeVisible = !isFullscreenChromeVisible
                        } else {
                            applyStageChrome(ElementCallChromeVisibility.Event.Tap)
                        }
                    }
                }
            }
            is StageChromeEvent.StageAppeared -> applyStageChrome(ElementCallChromeVisibility.Event.StageAppeared(event.isLandscape))
            is StageChromeEvent.StageRotated -> applyStageChrome(ElementCallChromeVisibility.Event.Rotated(event.isLandscape))
            is StageChromeEvent.UserScrolled -> {
                pendingReturn.cancel()
                applyStageChrome(ElementCallChromeVisibility.Event.UserScrolled(event.towardEnd))
            }
            StageChromeEvent.ScrollIdle -> {
                if (stageChrome.isAwaitingReturn) {
                    pendingReturn.cancel()
                    pendingReturn.job = chromeScope.launch {
                        delay(CHROME_RETURN_DELAY_MS)
                        applyStageChrome(ElementCallChromeVisibility.Event.ScrollIdleElapsed)
                    }
                }
            }
            is StageChromeEvent.ScreenReaderChanged -> applyStageChrome(ElementCallChromeVisibility.Event.ScreenReader(event.isRunning))
            is SmallCallEvent.MoveOwnTile -> spotlightMemory.ownTileCorner = event.corner
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

    return current.toState(
        videoFrames = videoFrames,
        tiles = tiles,
        spotlight = spotlight,
        fullscreenTileId = fullscreenTileId,
        isFullscreenChromeVisible = isFullscreenChromeVisible,
        isStageChromeVisible = tiles.isEmpty() || stageChrome.isVisible,
        ownTileCorner = spotlightMemory.ownTileCorner,
        eventSink = ::handleEvent,
    )
}

private class JobRef {
    var job: Job? = null
    val isActive: Boolean get() = job?.isActive == true

    fun cancel() {
        job?.cancel()
        job = null
    }
}

/**
 * Our own tile first, then the core's order untouched: every reference, with its record where the
 * window let one through (spec 003 R1, R54).
 *
 * First because the self view has to go somewhere and the core has no opinion: last would put us at
 * the end of a big call, and first is where iOS puts it. Our mute and camera come from the call
 * rather than from the core's tile, so a tap shows on the badge before the round trip does.
 */
internal fun ElementCallSnapshot.callTiles(): ImmutableList<CallTileData> {
    val own = ownTile?.let {
        it.copy(isHero = false, isMicrophoneMuted = isMicrophoneMuted, hasVideo = isCameraEnabled)
            .toCallTileData(roomMembers, isLocal = true, isFrontCamera = isFrontCamera)
    }
    val ranked = roster.order.map { ref -> ref.toCallTileData(roster.detail[ref.id], roomMembers, isFrontCamera = isFrontCamera) }
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
    spotlight: CallSpotlight.Choice,
    fullscreenTileId: String?,
    isFullscreenChromeVisible: Boolean,
    isStageChromeVisible: Boolean,
    ownTileCorner: ElementCallOwnTileCorner,
    eventSink: (ElementCallScreenEvent) -> Unit,
) = ElementCallScreenState(
    connection = this?.connection ?: ElementCallConnection.RequestingPermission,
    memberCount = this?.memberCount ?: 0,
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
    isCameraSwitchAvailable = this?.isCameraSwitchAvailable != false,
    isCameraPermissionGranted = this?.isCameraPermissionGranted == true,
    isScreenShareAvailable = this?.isScreenShareAvailable == true,
    isScreenSharing = this?.isScreenSharing == true,
    isTileStatsVisible = this?.isTileStatsVisible == true,
    videoFrames = videoFrames,
    roomName = this?.roomName,
    isDm = this?.isDm == true,
    connectedAtElapsedMs = this?.connectedAtElapsedMs,
    tiles = tiles,
    spotlight = spotlight,
    fullscreenTileId = fullscreenTileId,
    isFullscreenChromeVisible = isFullscreenChromeVisible,
    isStageChromeVisible = isStageChromeVisible,
    ownTileCorner = ownTileCorner,
    libraryVersion = ElementCallVersion.library,
    coreVersion = ElementCallVersion.core,
    eventSink = eventSink,
)
