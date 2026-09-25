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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallController
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.rtc.MatrixRtcScreenCaptureToken
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import io.element.android.call.ui.theme.ElementCallStyle
import io.element.android.call.ui.theme.ElementCallTheme
import io.element.android.call.ui.util.KeepScreenOn
import io.element.android.call.ui.util.scaffoldScrollableContentInsets
import kotlinx.coroutines.flow.Flow
import timber.log.Timber

/**
 * Draws the running call around the host's content: docked as a bar, floating as a tile, or covering
 * everything, and as a single tile when the Activity is in picture-in-picture. This is the whole of
 * spec rule R9: the minimized UI is the component's, where it sits is the host's.
 *
 * Both minimized and maximized states render the same call, held by [controller], so moving between
 * them is a Compose transition rather than a task switch - nothing reconnects, no media is
 * renegotiated, and the audio does not gap. That is the difference this whole component is meant to
 * show: the Element Call WebView lives in its own Activity and its own task, so it can only ever be
 * all of the screen or none of it.
 *
 * Permissions are asked here rather than in the controller, because only an Activity can ask, and
 * this is composed inside one. The controller keeps the `RequestingPermission` state and the
 * idempotent answers.
 *
 * @param controller the call to draw, from `ElementCallStack.controller`.
 * @param modifier applied to whatever is drawn at the root: the host's content, or the call over it.
 * @param style the host's design system, or null for the defaults. Read at draw time, so a host that
 * re-brands per session reaches the call.
 * @param content the host's own content, composed underneath with the bar's height consumed from its
 * top window inset, so a scaffold below slides down rather than jumping when the bar appears.
 */
@Composable
fun ElementCallOverlay(
    controller: ElementCallController,
    modifier: Modifier = Modifier,
    style: ElementCallStyle? = null,
    content: @Composable (Modifier) -> Unit,
) {
    val call by controller.state.collectAsState()
    val current = call
    if (current == null) {
        content(modifier)
        return
    }

    ElementCallTheme(style = style) {
        // A floating window is a couple of centimetres of screen. Everything that makes the call
        // screen usable - controls, the top bar, the strip of other people, the app behind it - is
        // either unreadable or untappable at that size, so PiP draws the one thing worth seeing and
        // nothing else. This is also the whole of the PiP implementation, because the call is ours:
        // the WebView path has to ask Element Call over the widget API whether it may enter PiP, and
        // hangs the call up if the answer is no.
        val isInPictureInPicture by controller.isInPictureInPicture.collectAsState()
        if (isInPictureInPicture) {
            ElementCallPictureInPictureView(call = current, videoFrames = controller::videoFrames)
        } else {
            CallInApp(controller = controller, current = current, modifier = modifier, content = content)
        }
    }
}

@Composable
private fun CallInApp(
    controller: ElementCallController,
    current: ElementCallSnapshot,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
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
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            controller.setMicrophonePermissionGranted(true)
        } else {
            microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // A video call needs the camera from the moment it connects, so it is asked for up front
    // rather than when the user reaches for the button - which is right for an audio call, where
    // the camera may never be wanted at all, and wrong here.
    //
    // Sequenced after the microphone rather than alongside it, so the user answers one dialog at
    // a time and each is asked in context. A denial is final: the keys do not change, so this
    // does not ask again for the rest of the call.
    LaunchedEffect(current.callData.isAudioCall, current.isMicrophonePermissionGranted) {
        if (current.callData.isAudioCall || !current.isMicrophonePermissionGranted) return@LaunchedEffect
        if (current.isCameraPermissionGranted) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            controller.setCameraPermissionGranted(true)
        } else {
            cameraLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val navigator = remember {
        object : ElementCallNavigator {
            // The call disappearing is what removes this from the composition, so there is
            // nothing to close - unlike an Activity host, which would have a window to finish.
            override fun close() = Unit

            override fun requestCameraPermission() {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    controller.setCameraPermissionGranted(true)
                } else {
                    cameraLauncher.launch(Manifest.permission.CAMERA)
                }
            }

            override fun requestScreenCapture() {
                val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                if (projectionManager == null) {
                    Timber.w("ElementCall: no MediaProjectionManager, cannot share the screen")
                    return
                }
                screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
        }
    }

    // The call screen is something you look at without touching - a video tile, someone else's
    // shared screen - so the display timing out mid-call is a bug rather than a saving. A WebView
    // call gets this from its own Activity adding FLAG_KEEP_SCREEN_ON to its own window; this has
    // no window of its own to add it to.
    //
    // Only while maximized. Minimized, the user has said they want to use the app, and their
    // scrolling keeps the screen alive on its own - holding it on through a long audio call
    // spent in someone's pocket would be the opposite of helpful. Audio held to the ear is a
    // third case and already handled: the proximity wake lock in the audio device controller
    // blanks the screen deliberately, and this does not fight it.
    KeepScreenOn(keepScreenOn = current.isMaximized)

    BackHandler(enabled = current.isMaximized) {
        controller.setMaximized(false)
    }

    // How a minimized call shows itself depends on whether it has a picture. A voice call docks as
    // a bar above the content, per the design; a video call floats as a draggable tile, because a
    // 56dp strip is no way to show video and because the whole reason to minimize a video call is
    // to keep watching it. Android's own picture-in-picture cannot do this job - it is an
    // out-of-app mode and only starts once the user has left the app - so the in-app case has to
    // be drawn by us, and native PiP takes over at the door. See `ElementCallFloatingTile`.
    val isMinimizedAsTile = !current.isMaximized && current.hasVideo
    val isBarVisible = !current.isMaximized && !isMinimizedAsTile
    Column(modifier = modifier) {
        val statusBarTopPadding = if (LocalInspectionMode.current) {
            24.dp
        } else {
            scaffoldScrollableContentInsets.asPaddingValues().calculateTopPadding()
        }
        // Animated in step with the bar so the content below slides rather than jumping the
        // moment the bar is asked for: the same problem a connectivity banner above every screen
        // has, and the same answer.
        val topWindowInset by animateDpAsState(
            targetValue = if (isBarVisible) statusBarTopPadding + MINIMIZED_CALL_BAR_HEIGHT else 0.dp,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 1.dp),
            label = "call-bar-insets-animation",
        )

        AnimatedVisibility(
            visible = isBarVisible,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            ElementCallMinimizedBar(
                call = current,
                onToggleMicrophone = { controller.setMicrophoneMuted(!current.isMicrophoneMuted) },
                onHangUp = controller::hangUp,
                onClick = { controller.setMaximized(true) },
            )
        }

        Box {
            // Kept composed underneath rather than swapped out: this is the host's whole
            // navigation tree, and tearing it down to show a call would lose every screen's
            // scroll position and re-run every presenter when the call was minimized again.
            content(Modifier.consumeWindowInsets(PaddingValues(top = topWindowInset)))

            FloatingCall(
                isVisible = isMinimizedAsTile,
                call = current,
                videoFrames = controller::videoFrames,
                onClick = { controller.setMaximized(true) },
            )

            MaximizedCall(
                isMaximized = current.isMaximized,
                controller = controller,
                navigator = navigator,
            )
        }
    }
}

/**
 * A minimized video call, floating over the app.
 *
 * Its own composable for the same reason [MaximizedCall] is: inside the overlay's `Column` an
 * `AnimatedVisibility` resolves to the `ColumnScope` overload, which is not what a tile drawn over
 * the content wants. It takes none of the content's space, unlike the bar, so nothing below reflows
 * when it appears.
 */
@Composable
private fun FloatingCall(
    isVisible: Boolean,
    call: ElementCallSnapshot,
    videoFrames: (memberId: String, kind: MatrixRtcStreamKind) -> Flow<MatrixRtcVideoFrame>,
    onClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f),
    ) {
        ElementCallFloatingTile(
            call = call,
            videoFrames = videoFrames,
            onClick = onClick,
            modifier = Modifier.systemBarsPadding(),
        )
    }
}

/**
 * The call, covering the app.
 *
 * Its own composable rather than inline, because inside the overlay's `Column` an `AnimatedVisibility`
 * resolves to the `ColumnScope` overload, which is not what a layer stacked over the content wants.
 *
 * It grows out of the top rather than sliding up from the bottom, because the top is where the bar
 * it came from is: the screen scales up from the bar's own edge as the bar collapses into it, so the
 * two read as one thing changing size. Sliding in from off-screen reads as a second screen arriving,
 * which is exactly the impression the Activity-per-call architecture gives and the one worth losing.
 */
@Composable
private fun MaximizedCall(
    isMaximized: Boolean,
    controller: ElementCallController,
    navigator: ElementCallNavigator,
) {
    AnimatedVisibility(
        visible = isMaximized,
        enter = fadeIn(MAXIMIZE_FADE_SPEC) + scaleIn(MAXIMIZE_SCALE_SPEC, initialScale = MAXIMIZE_SCALE, transformOrigin = FROM_THE_BAR),
        exit = fadeOut(MAXIMIZE_FADE_SPEC) + scaleOut(MAXIMIZE_SCALE_SPEC, targetScale = MAXIMIZE_SCALE, transformOrigin = FROM_THE_BAR),
    ) {
        val state = rememberElementCallScreenState(controller = controller, navigator = navigator)
        ElementCallScreen(state = state, modifier = Modifier.fillMaxSize())
    }
}

/** The top edge, where the minimized bar is docked. */
private val FROM_THE_BAR = TransformOrigin(pivotFractionX = 0.5f, pivotFractionY = 0f)

/**
 * Barely a shrink. The call screen is nearly the whole window, so anything more than a hint of scale
 * turns "this got bigger" into "this flew in from somewhere", and reveals the app behind it.
 */
private const val MAXIMIZE_SCALE = 0.93f

private val MAXIMIZE_SCALE_SPEC = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/** Faster than the scale, so the app underneath is covered before the screen finishes settling. */
private val MAXIMIZE_FADE_SPEC = tween<Float>(durationMillis = 140)
