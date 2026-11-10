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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallController
import io.element.android.call.api.ElementCallPreferences
import io.element.android.call.api.ElementCallRoomMember
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.ElementCallVersion
import io.element.android.call.api.rtc.MatrixRtcConnectionState
import io.element.android.call.api.rtc.MatrixRtcReactionKind
import io.element.android.call.api.rtc.MatrixRtcSignallingHealth
import io.element.android.call.api.rtc.MatrixRtcTileKind
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import io.element.android.call.api.rtc.id.UserId
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

    // Saved across rotation and backgrounding; picture-in-picture and minimising dispose this presenter, so the sheet
    // is closed when the screen comes back (037 R12a).
    var isPreferencesVisible by rememberSaveable { mutableStateOf(false) }
    val preferences by controller.preferences.collectAsState()
    val latestSnapshot by rememberUpdatedState(current)
    // Named once, as the reaction appears, and kept for its flight whatever the sender does next (037 R7, R7a).
    val reactions = remember(controller) {
        controller.reactions.map { reaction ->
            val snapshot = latestSnapshot
            ElementCallReactionBubbleData(
                id = reaction.id,
                emoji = reaction.emoji,
                senderName = callDisplayName(snapshot?.roomMembers?.get(reaction.userId), reaction.userId),
                isLocal = snapshot?.ownTile?.id?.memberId == reaction.memberId,
                spread = reaction.spread,
            )
        }
    }

    val tiles = current?.callTiles() ?: persistentListOf()
    val extraActions = extraActions(
        showsRaiseHandControl = current?.showsRaiseHandControl == true,
        showsReactionControl = current?.showsReactionControl == true,
        isScreenShareAvailable = current?.isScreenShareAvailable == true,
    )
    val reactionCatalogue = remember(controller) { lazy { controller.reactionCatalogue.toImmutableList() } }
    // Not saved, unlike Preferences: the Activity keeps this across rotation, which closes it on purpose (009 R27).
    var presentedSheet by remember { mutableStateOf<ElementCallSheet?>(null) }
    val isConnected = current?.connection == ElementCallConnection.Connected
    // Gone with the call, and with what it offered: a sheet whose rows have left is not reopened when they come back.
    LaunchedEffect(isConnected, extraActions) {
        val isStale = when (presentedSheet) {
            ElementCallSheet.MORE -> extraActions.toBarSlot() != ElementCallBarSlot.More
            ElementCallSheet.REACTIONS -> ElementCallBarAction.REACTIONS !in extraActions
            null -> false
        }
        if (!isConnected || isStale) presentedSheet = null
    }
    // While a DM call rings, the chrome stays up and a tap on the stage leaves it there (030 R19).
    val isRinging = current?.connection is ElementCallConnection.Calling || current?.connection is ElementCallConnection.RingNotSent

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
    // At pickup the chrome takes the call layout's starting state, as when the stage first appears (030 R19).
    var wasRinging by remember { mutableStateOf(isRinging) }
    LaunchedEffect(isRinging) {
        if (wasRinging && !isRinging) stageChrome.isLandscape?.let { applyStageChrome(ElementCallChromeVisibility.Event.StageAppeared(it)) }
        wasRinging = isRinging
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
        // A remote tile whose media is interrupted draws its avatar, not its last frame (034 R34).
        .filter { it.hasVideo && (it.isLocal || !it.isMediaInterrupted) }
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
            ElementCallScreenEvent.OpenAppSettings -> navigator.openAppSettings()
            ElementCallScreenEvent.SwitchCamera -> controller.switchCamera()
            ElementCallScreenEvent.ToggleTileStats -> controller.toggleTileStats()
            ElementCallScreenEvent.ShowPreferences -> isPreferencesVisible = true
            ElementCallScreenEvent.DismissPreferences -> isPreferencesVisible = false
            is ElementCallScreenEvent.SetReactionsPreference -> controller.setReactionsPreference(event.preference)
            is ElementCallScreenEvent.SetSoundEffectsVolume -> controller.setSoundEffectsVolume(event.volume)
            ElementCallScreenEvent.PlaySoundEffectsVolumeSample -> controller.playSoundEffectsVolumeSample()
            is ElementCallScreenEvent.SetVideoConstraints ->
                controller.setVideoConstraints(event.memberId, event.kind, event.constraints)
            is ElementCallScreenEvent.SetComposedTiles -> controller.setComposedTiles(event.tileIds)
            is ElementCallScreenEvent.SetDetailWindow -> controller.setDetailWindow(event.window)
            is ElementCallScreenEvent.SetPictureInPictureSource -> controller.setPictureInPictureSource(event.source)
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
                if (isRinging) return
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
            is StageChromeEvent.StageRotated -> {
                presentedSheet = null
                applyStageChrome(ElementCallChromeVisibility.Event.Rotated(event.isLandscape))
            }
            StageChromeEvent.ScreenStopped -> presentedSheet = null
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
                presentedSheet = null
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
            ElementCallScreenEvent.Minimize -> {
                presentedSheet = null
                navigator.minimize()
            }
            ElementCallScreenEvent.ToggleRaisedHand -> {
                // From the sheet, closed so the banner is in view (009 R17).
                presentedSheet = null
                controller.setHandRaised(current?.ownTile?.handRaisedAtMs == null)
            }
            is ElementCallScreenEvent.Present -> presentedSheet = event.sheet
            ElementCallScreenEvent.DismissSheet -> presentedSheet = null
            is ElementCallScreenEvent.SendReaction -> {
                controller.sendReaction(event.kind)
                presentedSheet = null
            }
            ElementCallScreenEvent.DismissHandRequestFailure -> controller.clearHandRequestFailure()
            ElementCallScreenEvent.HangUp -> controller.hangUp()
        }
    }

    // The same choice the window makes, so the tile reporting where it is is the one the window shows.
    // Not ours: the window draws a placeholder rather than our own camera. Only while maximized: the
    // screen plays its way out after a minimise, and the floating tile reports from then on.
    val pictureInPictureTileId = current?.takeIf { it.isMaximized }
        ?.pictureInPictureCandidate(spotlightMemory.spotlightId)
        ?.takeIf { !it.isLocal }
        ?.let { candidate -> tiles.firstOrNull { it.id == candidate.id }?.tileId }

    return current.toState(
        videoFrames = videoFrames,
        tiles = tiles,
        spotlight = spotlight,
        fullscreenTileId = fullscreenTileId,
        isFullscreenChromeVisible = isFullscreenChromeVisible,
        isStageChromeVisible = tiles.isEmpty() || isRinging || stageChrome.isVisible,
        ownTileCorner = spotlightMemory.ownTileCorner,
        pictureInPictureTileId = pictureInPictureTileId,
        extraActions = extraActions,
        presentedSheet = presentedSheet,
        // Read once the picker opens: the call has joined by then, so the native core is already loaded, never on this thread.
        reactionCatalogue = if (presentedSheet == ElementCallSheet.REACTIONS) reactionCatalogue.value else persistentListOf(),
        reactions = reactions,
        isPreferencesVisible = isPreferencesVisible,
        preferences = preferences,
        isDeveloperModeEnabled = controller.isDeveloperModeEnabled,
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
    val ranked = roster.order.map { ref ->
        ref.toCallTileData(roster.detail[ref.id], roomMembers, isFrontCamera = isFrontCamera, isMissingKeys = ref.id.memberId in membersMissingKeys)
    }
    return (listOfNotNull(own, connection.ringingCallee(roomMembers)) + ranked).showingHands(!isOneToOne).toImmutableList()
}

/** The callee a DM call is ringing, in the slot their tile will take at pickup (030 R15, R21). */
internal fun ElementCallConnection.ringingCallee(roomMembers: Map<UserId, ElementCallRoomMember>): CallTileData? {
    val (callee, ring) = when (val connection = this) {
        is ElementCallConnection.Calling -> connection.callee to CallRing.CALLING
        is ElementCallConnection.RingNotSent -> connection.callee to CallRing.NOT_SENT
        else -> return null
    }
    if (callee == null) return null
    return CallTileData(
        memberId = "$RINGING_TILE_PREFIX${callee.value}",
        userId = callee,
        roomMember = roomMembers[callee],
        isLocal = false,
        isMuted = false,
        isActiveSpeaker = false,
        hasVideo = false,
        isVideoMirrored = false,
        ring = ring,
    )
}

/** One to one, no tile shows a hand, ours and a fullscreen one included; the hands stay up (009 R33). */
internal fun List<CallTileData>.showingHands(isShown: Boolean): List<CallTileData> =
    if (isShown) this else map { it.copy(handPosition = null) }

/** From three people, ourselves included, or while our hand is up so it can come down (009 R2, R33). */
private val ElementCallSnapshot.showsRaiseHandControl: Boolean
    get() = ownTile != null && (ownTile?.handRaisedAtMs != null || peopleCount >= MIN_PEOPLE_FOR_HANDS_AND_REACTIONS)

/** Under the hand's count with no exception: a reaction ends by itself (009 R33). */
private val ElementCallSnapshot.showsReactionControl: Boolean
    get() = ownTile != null && peopleCount >= MIN_PEOPLE_FOR_HANDS_AND_REACTIONS

/** Ourselves and every remote person; a share is a second tile for one of them. */
private val ElementCallSnapshot.peopleCount: Int
    get() = 1 + roster.order.count { it.id.kind == MatrixRtcTileKind.PERSON }

private const val MIN_PEOPLE_FOR_HANDS_AND_REACTIONS = 3

/** Never a member id: the core mints those, and none starts this way. */
private const val RINGING_TILE_PREFIX = "ringing:"

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
    pictureInPictureTileId: String?,
    extraActions: ImmutableList<ElementCallBarAction>,
    presentedSheet: ElementCallSheet?,
    reactionCatalogue: ImmutableList<MatrixRtcReactionKind>,
    reactions: Flow<ElementCallReactionBubbleData>,
    isPreferencesVisible: Boolean,
    preferences: ElementCallPreferences,
    isDeveloperModeEnabled: Boolean,
    eventSink: (ElementCallScreenEvent) -> Unit,
) = ElementCallScreenState(
    connection = this?.connection ?: ElementCallConnection.RequestingPermission,
    isAudioCall = this?.callData?.isAudioCall == true,
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
    isScreenShareStartEnabled = this?.ownTile?.connection.let { it != MatrixRtcConnectionState.RECONNECTING && it != MatrixRtcConnectionState.UNREACHABLE },
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
    isHandRaised = this?.ownTile?.handRaisedAtMs != null,
    extraActions = extraActions,
    presentedSheet = presentedSheet,
    reactionCatalogue = reactionCatalogue,
    isRaiseHandEnabled = this?.signalling !is MatrixRtcSignallingHealth.Unreachable,
    isReactionControlEnabled = this?.signalling !is MatrixRtcSignallingHealth.Unreachable,
    // Over a live call only: a failed one has its own screen.
    interruptions = this?.interruptions?.takeIf { connection == ElementCallConnection.Connected } ?: persistentListOf(),
    handRequestFailure = this?.handRequestFailure,
    pictureInPictureTileId = pictureInPictureTileId,
    // The call screen's own Activity knows, and sets it on the way out.
    isLeavingForPictureInPicture = false,
    libraryVersion = ElementCallVersion.library,
    coreVersion = ElementCallVersion.core,
    reactions = reactions,
    isPreferencesVisible = isPreferencesVisible,
    reactionsPreference = preferences.reactions,
    soundEffectsVolume = preferences.soundEffectsVolume,
    isDeveloperModeEnabled = isDeveloperModeEnabled,
    eventSink = eventSink,
)
