/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.round
import androidx.core.content.ContextCompat
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallController
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.ElementCallWindowRect
import io.element.android.call.api.rtc.MatrixRtcDetailWindow
import io.element.android.call.api.rtc.MatrixRtcScreenCaptureToken
import io.element.android.call.ui.util.KeepScreenOn
import timber.log.Timber

/**
 * The call as [ElementCallActivity] draws it: the full screen, or the one tile in picture-in-picture.
 *
 * A floating window is a couple of centimetres of screen, so picture-in-picture draws the one thing worth
 * seeing and nothing else.
 */
@Composable
internal fun ElementCallWindow(
    controller: ElementCallController,
    isInPictureInPicture: Boolean,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    /** The system is about to shrink the screen without waiting for us: Home, from Android 12. */
    isLeavingForPictureInPicture: Boolean = false,
    /** The window's content is on screen, so the system can take its picture to shrink. */
    onPictureInPictureShow: () -> Unit = {},
) {
    val current = controller.state.collectAsState().value ?: return
    val spotlightMemory = rememberCallSpotlightMemory(controller)
    CallLayoutEffects(controller = controller, current = current, spotlightMemory = spotlightMemory)
    val entry = controller.pictureInPictureEntry.collectAsState().value
    if (isInPictureInPicture) {
        UnmountedStageWindow(controller = controller, current = current, spotlightMemory = spotlightMemory, isStageMounted = false)
    } else {
        CallScreenWindow(
            controller = controller,
            current = current,
            spotlightMemory = spotlightMemory,
            isLeavingForPictureInPicture = isLeavingForPictureInPicture,
            onMinimize = onMinimize,
            onClose = onClose,
        )
    }
    // One call site for the way in and the window, so the renderer that drew the way in carries on in the
    // window: a new one would be black until its first frame, which is what the system would shrink.
    if (isInPictureInPicture || entry != null) {
        // Unseen until it shows something, so the call stays on screen rather than a black renderer.
        var isShown by remember { mutableStateOf(false) }
        // Over black on the way in: the system crops the whole screen down onto the rectangle as it shrinks it,
        // so what is around it shows for the first frames, and that should not be the grid or its chrome.
        AtWindowRect(
            rect = entry.takeUnless { isInPictureInPicture },
            modifier = Modifier
                .graphicsLayer { alpha = if (isShown || isInPictureInPicture) 1f else 0f }
                .background(Color.Black),
        ) {
            ElementCallPictureInPictureContent(
                call = current,
                videoFrames = controller::videoFrames,
                spotlightId = spotlightMemory.spotlightId,
                onShow = {
                    isShown = true
                    onPictureInPictureShow()
                },
            )
        }
    }
}

/** [content] at exactly [rect] of the window wherever this is composed, or over all of it with no [rect]; [modifier] is the whole window's. */
@Composable
private fun AtWindowRect(rect: ElementCallWindowRect?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var origin by remember { mutableStateOf(IntOffset.Zero) }
    Box(modifier = modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow().round() }) {
        Box(
            modifier = if (rect == null) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .offset { IntOffset(rect.left - origin.x, rect.top - origin.y) }
                    .layout { measurable, _ ->
                        val placeable = measurable.measure(Constraints.fixed(rect.width, rect.height))
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
            },
        ) {
            content()
        }
    }
}

/**
 * What the call needs from the layout wherever it is drawn, the bar included: the small layout's threshold,
 * and the window that keeps following the speaker while the screen is gone (019 R15).
 */
@Composable
internal fun CallLayoutEffects(
    controller: ElementCallController,
    current: ElementCallSnapshot,
    spotlightMemory: CallSpotlightMemory,
) {
    LaunchedEffect(controller) { controller.setRankingThreshold(SmallCallLayout.RANKING_THRESHOLD) }
    val tiles = remember(current) { current.callTiles() }
    LaunchedEffect(tiles) {
        if (!SmallCallLayout.applies(tiles)) return@LaunchedEffect
        val speaker = SmallCallLayout.speaker(tiles, spotlightMemory.smallCallSpeakerId)
        spotlightMemory.smallCallSpeakerId = speaker
        spotlightMemory.spotlightId = SmallCallLayout.pictureInPictureTile(tiles, speaker)?.id
    }
}

/**
 * The detail window while the stage is unmounted - minimised, or in Picture in Picture: the head of
 * the order plus the tile shown, and nothing else composed (contract B8). The stage declares its own
 * while it is mounted, and this must not compete with it: [isStageMounted] is false only once the
 * stage is gone, exit animation included, or the two would take turns for as long as it runs.
 */
@Composable
internal fun UnmountedStageWindow(
    controller: ElementCallController,
    current: ElementCallSnapshot,
    spotlightMemory: CallSpotlightMemory,
    isStageMounted: Boolean,
) {
    val shown = current.pictureInPictureCandidate(spotlightMemory.spotlightId)?.takeUnless { it.isRingingCallee }?.id
    LaunchedEffect(isStageMounted, shown) {
        if (isStageMounted) return@LaunchedEffect
        controller.setComposedTiles(setOfNotNull(shown))
        controller.setDetailWindow(MatrixRtcDetailWindow(ranks = HEAD_RANGE, also = setOfNotNull(shown)))
    }
}

@Composable
private fun CallScreenWindow(
    controller: ElementCallController,
    current: ElementCallSnapshot,
    spotlightMemory: CallSpotlightMemory,
    isLeavingForPictureInPicture: Boolean,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
) {
    val permissions = rememberCallPermissions(controller = controller, current = current)
    val navigator = remember(permissions) {
        object : ElementCallNavigator {
            override fun close() = onClose()

            override fun minimize() = onMinimize()

            override fun requestCameraPermission() = permissions.requestCamera()

            override fun requestScreenCapture() = permissions.requestScreenCapture()
        }
    }

    // The call screen is something you look at without touching - a video tile, someone else's
    // shared screen - so the display timing out mid-call is a bug rather than a saving. Audio held
    // to the ear is already handled: the proximity wake lock blanks the screen deliberately.
    KeepScreenOn(keepScreenOn = current.isMaximized)

    // The stage counts as mounted until its exit animation has finished, see UnmountedStageWindow.
    val maximizedState = remember { MutableTransitionState(current.isMaximized) }
    maximizedState.targetState = current.isMaximized
    UnmountedStageWindow(
        controller = controller,
        current = current,
        spotlightMemory = spotlightMemory,
        isStageMounted = maximizedState.currentState || maximizedState.targetState,
    )
    MaximizedCall(
        visibleState = maximizedState,
        controller = controller,
        navigator = navigator,
        spotlightMemory = spotlightMemory,
        isLeavingForPictureInPicture = isLeavingForPictureInPicture,
    )

    if (current.isMaximized) {
        BackHandler(onBack = onMinimize)
    }
}

internal data class CallPermissions(
    val requestCamera: () -> Unit,
    val requestScreenCapture: () -> Unit,
)

/**
 * Asks for the microphone, and the camera on a video call, as the call needs them. Here rather than in the
 * controller because only an Activity can ask; the controller keeps the `RequestingPermission` state and
 * the idempotent answers.
 */
@Composable
internal fun rememberCallPermissions(controller: ElementCallController, current: ElementCallSnapshot): CallPermissions {
    val context = LocalContext.current
    val microphoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        controller.setMicrophonePermissionGranted(granted)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        controller.setCameraPermissionGranted(granted)
    }
    // Not a permission request: the screen-capture dialog returns an Intent that *is* the grant,
    // single-use, so there is nothing to remember and nothing to check before asking. A refusal
    // comes back as a non-OK result code and simply means no share.
    val screenCaptureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val resultData = result.data
        if (result.resultCode == Activity.RESULT_OK && resultData != null) {
            controller.setScreenShareEnabled(MatrixRtcScreenCaptureToken(resultData))
        } else {
            Timber.i("ElementCall: screen capture was refused")
        }
    }

    // Keyed on the state that needs answering rather than on the call, so it asks once and does
    // not ask again on every recomposition of a call that is already connected.
    LaunchedEffect(current.connection) {
        if (current.connection != ElementCallConnection.RequestingPermission) return@LaunchedEffect
        if (context.isGranted(Manifest.permission.RECORD_AUDIO)) {
            controller.setMicrophonePermissionGranted(true)
        } else {
            microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // A video call needs the camera from the moment it connects, so it is asked for up front, after
    // the microphone so the user answers one dialog at a time. A denial is final: the keys do not
    // change, so this does not ask again for the rest of the call.
    LaunchedEffect(current.callData.isAudioCall, current.isMicrophonePermissionGranted) {
        if (current.callData.isAudioCall || !current.isMicrophonePermissionGranted) return@LaunchedEffect
        if (current.isCameraPermissionGranted) return@LaunchedEffect
        if (context.isGranted(Manifest.permission.CAMERA)) {
            controller.setCameraPermissionGranted(true)
        } else {
            cameraLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    return remember(controller) {
        CallPermissions(
            requestCamera = {
                if (context.isGranted(Manifest.permission.CAMERA)) {
                    controller.setCameraPermissionGranted(true)
                } else {
                    cameraLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            requestScreenCapture = {
                val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                if (projectionManager == null) {
                    Timber.w("ElementCall: no MediaProjectionManager, cannot share the screen")
                } else {
                    screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
                }
            },
        )
    }
}

private fun Context.isGranted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * The call, covering the window. It grows out of the top, where the bar it came from is docked, so a
 * call minimized and restored inside one window reads as one thing changing size.
 */
@Composable
private fun MaximizedCall(
    visibleState: MutableTransitionState<Boolean>,
    controller: ElementCallController,
    navigator: ElementCallNavigator,
    spotlightMemory: CallSpotlightMemory,
    isLeavingForPictureInPicture: Boolean,
) {
    AnimatedVisibility(
        visibleState = visibleState,
        enter = fadeIn(MAXIMIZE_FADE_SPEC) + scaleIn(MAXIMIZE_SCALE_SPEC, initialScale = MAXIMIZE_SCALE, transformOrigin = FROM_THE_BAR),
        exit = fadeOut(MAXIMIZE_FADE_SPEC) + scaleOut(MAXIMIZE_SCALE_SPEC, targetScale = MAXIMIZE_SCALE, transformOrigin = FROM_THE_BAR),
    ) {
        val state = rememberElementCallScreenState(controller = controller, navigator = navigator, spotlightMemory = spotlightMemory)
        ElementCallScreen(state = state.copy(isLeavingForPictureInPicture = isLeavingForPictureInPicture), modifier = Modifier.fillMaxSize())
    }
}

/** The top edge, where the minimized bar is docked. */
private val FROM_THE_BAR = TransformOrigin(pivotFractionX = 0.5f, pivotFractionY = 0f)

/** Barely a shrink: anything more turns "this got bigger" into "this flew in from somewhere". */
private const val MAXIMIZE_SCALE = 0.93f

private val MAXIMIZE_SCALE_SPEC = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/** Faster than the scale, so what is underneath is covered before the screen finishes settling. */
private val MAXIMIZE_FADE_SPEC = tween<Float>(durationMillis = 140)
