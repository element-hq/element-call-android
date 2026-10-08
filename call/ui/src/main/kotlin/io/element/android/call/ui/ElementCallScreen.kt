/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalLayoutApi::class)

package io.element.android.call.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.audio.CallAudioDeviceType
import io.element.android.call.ui.preview.ElementCallPreview
import io.element.android.call.ui.preview.PreviewsDayNight
import io.element.android.call.ui.theme.ElementCallTheme

/**
 * The call, as a product rather than as an instrument.
 *
 * Spotlight above a strip, per the design: one member large - whoever is talking - and everyone else
 * in a grid below. The diagnostics screen this replaces is still reachable from developer settings,
 * because it is what the RTC library feedback was written from.
 */
@Composable
fun ElementCallScreen(
    state: ElementCallScreenState,
    modifier: Modifier = Modifier,
) {
    // Painted from this component's palette, not Material's default, which comes from the host's
    // theme: in a light-themed app the call screen would otherwise be white wherever the tiles do not
    // cover it - the connecting placeholder, and the letterboxing around a portrait video.
    Surface(
        modifier = modifier.fillMaxSize(),
        color = ElementCallTheme.colors.bgCanvas,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isLandscape = maxWidth > maxHeight
            // The control bar floats over the bottom of the stage in both orientations (spec 003
            // R43), so the stage is told how much of its bottom the bar covers and scrolls its last
            // row clear of it (R44).
            val bottomInset = LocalCallStageTestHooks.current?.bottomInset ?: WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
            val controlsClearance = CONTROLS_HEIGHT + bottomInset
            // The stage has the same bounds in every arrangement, fullscreen included, and the chrome
            // floats over it: a stage that moved or resized when a tile went fullscreen would take the
            // whole grid with it on the first frame of the move (spec 000 R7). Held upright, the top
            // bar is opaque and the grid is arranged below it; held sideways it floats over the tiles,
            // because stacked, the two bars take about two fifths of a phone's landscape height,
            // which is exactly the height the video wanted. One call site, so the scroll offset, the
            // linger and the rest of what the stage remembers survive rotation and fullscreen (003 R63, R66).
            // Ignoring visibility, because the status bar comes and goes with the chrome in landscape
            // (spec 014 R8) and nothing the stage is given may change with the chrome (R4).
            val statusBarTop = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
            // Kept while the bar is not drawn, so the grid does not move under a fullscreen tile.
            var bannerHeight by remember { mutableStateOf(0.dp) }
            val topClearance = if (isLandscape) 0.dp else statusBarTop + TOP_BAR_HEIGHT + bannerHeight
            val density = LocalDensity.current
            val controls = @Composable { controlsModifier: Modifier ->
                Box(
                    modifier = controlsModifier
                        .fillMaxWidth()
                        // Behind the floating controls, so they stay readable over a bright tile.
                        .background(Brush.verticalGradient(listOf(Color.Transparent, ElementCallTheme.colors.controlsScrim))),
                ) {
                    CallControlsBar(state, isCompact = isLandscape, modifier = Modifier.windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility))
                }
            }
            val reduceMotion = rememberReduceMotion()
            ReportScreenReader(state.eventSink)
            val fullscreen = state.fullscreenTile
            // One to one, the picture is the whole screen, so upright too the top bar goes with the
            // control bar; with any other count it stays (019 R30, 014 R30).
            val isFullBleed = SmallCallLayout.isFullBleed(state.tiles)
            val isTopBarHideable = isLandscape || isFullBleed
            // Held sideways the status bar stays away, chrome or not: over the picture it has no
            // background of its own, and it tells nothing worth the strip it takes (014 R8, as proposed).
            // One to one upright, it leaves with the top bar.
            CallSystemBars(isStatusBarHidden = isLandscape || isFullBleed && !state.isStageChromeVisible)
            val systemBars = WindowInsets.systemBarsIgnoringVisibility.asPaddingValues()
            val floatingInsets = FloatingInsets(
                left = systemBars.calculateLeftPadding(LayoutDirection.Ltr),
                top = when {
                    isLandscape -> if (state.isStageChromeVisible) statusBarTop + TOP_BAR_HEIGHT else 0.dp
                    // Into the room the top bar leaves, short of the status bar's strip.
                    isFullBleed && !state.isStageChromeVisible -> statusBarTop - topClearance
                    else -> 0.dp
                },
                right = systemBars.calculateRightPadding(LayoutDirection.Ltr),
                bottom = if (state.isStageChromeVisible) controlsClearance else bottomInset,
            )

            Box(modifier = Modifier.fillMaxSize()) {
                if (state.tiles.isEmpty()) {
                    ConnectingPlaceholder(state)
                } else {
                    ReportStageOrientation(isLandscape = isLandscape, eventSink = state.eventSink)
                    CallStage(
                        state = state,
                        controlsClearance = controlsClearance,
                        topClearance = topClearance,
                        fullscreenTop = statusBarTop,
                        floatingInsets = floatingInsets,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (!isTopBarHideable) {
                    // Faded rather than removed when a tile goes fullscreen, in step with the stage's scrim: removed
                    // at once, the rows scrolled under it showed until the growing tile covered them (000 R7).
                    AnimatedVisibility(
                        visible = fullscreen == null,
                        enter = fadeIn(tween(FULLSCREEN_SCRIM_OUT_MS)),
                        exit = fadeOut(tween(FULLSCREEN_SCRIM_IN_MS)),
                    ) {
                        // Opaque, so a row scrolled up passes under it; a drag on it is its own, as on the controls (003 B10).
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(ElementCallTheme.colors.bgCanvas)
                                .scrollable(rememberScrollableState { it }, Orientation.Vertical)
                                .statusBarsPadding(),
                        ) {
                            CallTopBar(state, modifier = Modifier.height(TOP_BAR_HEIGHT))
                            ScreenShareBanner(
                                state = state,
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .onSizeChanged { bannerHeight = with(density) { it.height.toDp() } },
                            )
                        }
                    }
                }
                if (fullscreen != null) {
                    // A tile filling the stage, with no chrome but the HUD when asked for (spec 000 R1, R8).
                    // The status bar keeps the canvas behind it rather than the grid rows under the bar.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsTopHeight(WindowInsets.statusBarsIgnoringVisibility)
                            .background(ElementCallTheme.colors.bgCanvas),
                    )
                    if (state.isFullscreenChromeVisible) {
                        Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)) {
                            CallFullscreenChrome(
                                tile = fullscreen,
                                onExitFullscreen = { state.eventSink(ElementCallScreenEvent.ExitFullscreen) },
                                controls = controls,
                            )
                        }
                    }
                } else if (isTopBarHideable) {
                    // Both bars over the picture, shown and hidden as one (014 R1, R2; 019 R30).
                    StageChrome(isVisible = state.isStageChromeVisible, edge = Alignment.Top, reduceMotion = reduceMotion) {
                        Box {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(statusBarTop + TOP_BAR_HEIGHT + TOP_SCRIM_EXTENT)
                                    .background(Brush.verticalGradient(listOf(ElementCallTheme.colors.controlsScrim, Color.Transparent))),
                            )
                            CallTopBar(state, modifier = Modifier.windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility))
                        }
                    }
                    // The banner is not chrome: it stays, and takes the top bar's place while that is away (R3).
                    val bannerTop by animateDpAsState(
                        targetValue = if (state.isStageChromeVisible) TOP_BAR_HEIGHT else 0.dp,
                        animationSpec = tween(CHROME_SLIDE_MS),
                        label = "bannerTop",
                    )
                    // Stacked so a share that is still running when the call becomes one-to-one does
                    // not draw its banner through the duration.
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility)
                            .padding(top = bannerTop),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ScreenShareBanner(state = state)
                        AnimatedVisibility(
                            visible = state.isStageChromeVisible,
                            enter = fadeIn(tween(CHROME_SLIDE_MS)),
                            exit = fadeOut(tween(CHROME_SLIDE_MS))
                        ) {
                            CallDurationLabel(state = state)
                        }
                    }
                    StageChrome(
                        isVisible = state.isStageChromeVisible,
                        edge = Alignment.Bottom,
                        reduceMotion = reduceMotion,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    ) {
                        controls(Modifier)
                    }
                } else {
                    CallDurationLabel(
                        state = state,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = topClearance + 12.dp),
                    )
                    // Upright only the control bar goes; the top bar stays (014 R30).
                    StageChrome(
                        isVisible = state.isStageChromeVisible,
                        edge = Alignment.Bottom,
                        reduceMotion = reduceMotion,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    ) {
                        controls(Modifier)
                    }
                }
            }
        }
    }
}

/**
 * Chrome that slides off the [edge] it sits on, or fades with animations removed (spec 014 R25).
 *
 * Removed once hidden, so it takes no touches and TalkBack cannot reach it, and moved as a whole, so
 * the buttons travel with their background.
 */
@Composable
private fun StageChrome(
    isVisible: Boolean,
    edge: Alignment.Vertical,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val fromTop = edge == Alignment.Top
    val enter: EnterTransition
    val exit: ExitTransition
    if (reduceMotion) {
        enter = fadeIn(tween(CHROME_SLIDE_MS))
        exit = fadeOut(tween(CHROME_SLIDE_MS))
    } else {
        enter = slideInVertically(tween(CHROME_SLIDE_MS)) { if (fromTop) -it else it } + fadeIn(tween(CHROME_SLIDE_MS))
        exit = slideOutVertically(tween(CHROME_SLIDE_MS)) { if (fromTop) -it else it } + fadeOut(tween(CHROME_SLIDE_MS))
    }
    AnimatedVisibility(visible = isVisible, modifier = modifier, enter = enter, exit = exit) {
        content()
    }
}

/** Tells the presenter the stage is up, and each time it changes shape after that (014 R10 to R12). */
@Composable
private fun ReportStageOrientation(isLandscape: Boolean, eventSink: (ElementCallScreenEvent) -> Unit) {
    val currentEventSink by rememberUpdatedState(eventSink)
    val hasAppeared = remember { mutableStateOf(false) }
    LaunchedEffect(isLandscape) {
        currentEventSink(if (hasAppeared.value) StageChromeEvent.StageRotated(isLandscape) else StageChromeEvent.StageAppeared(isLandscape))
        hasAppeared.value = true
    }
}

/** Tells the presenter whether TalkBack is exploring the screen, which keeps the chrome up (014 R24). */
@Composable
private fun ReportScreenReader(eventSink: (ElementCallScreenEvent) -> Unit) {
    val context = LocalContext.current
    val currentEventSink by rememberUpdatedState(eventSink)
    DisposableEffect(context) {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { currentEventSink(StageChromeEvent.ScreenReaderChanged(it)) }
        currentEventSink(StageChromeEvent.ScreenReaderChanged(manager?.isTouchExplorationEnabled == true))
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
}

/**
 * The screen's system bars: light icons, since the call is always dark, and the status bar hidden
 * while asked to (014 R8). The host's appearance and its status bar come back when the screen goes.
 *
 * The icons are asked for again on every change, because a rotation or the status bar coming back
 * can restore the window's own appearance underneath us.
 */
@Composable
private fun CallSystemBars(isStatusBarHidden: Boolean) {
    if (LocalInspectionMode.current) return
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window } ?: return
    val controller = remember(window, view) { WindowCompat.getInsetsController(window, view) }
    DisposableEffect(controller) {
        val wasLightStatusBars = controller.isAppearanceLightStatusBars
        val wasLightNavigationBars = controller.isAppearanceLightNavigationBars
        onDispose {
            controller.show(WindowInsetsCompat.Type.statusBars())
            controller.isAppearanceLightStatusBars = wasLightStatusBars
            controller.isAppearanceLightNavigationBars = wasLightNavigationBars
        }
    }
    val orientation = LocalConfiguration.current.orientation
    DisposableEffect(controller, isStatusBarHidden, orientation) {
        if (isStatusBarHidden) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {}
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Settings, Accessibility, Remove animations: Android's reduce motion (014 R25). */
@Composable
private fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/**
 * Who the call is with, centred as the design has it, with the way out of the screen at the start
 * and the overflow menu at the end.
 *
 * In a DM the room's name *is* the other person's name, but it arrives asynchronously, so until it
 * does the person in the spotlight stands in for it rather than leaving the bar blank.
 */
@Composable
private fun CallTopBar(state: ElementCallScreenState, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        IconButton(
            onClick = { state.eventSink(ElementCallScreenEvent.Minimize) },
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Icon(
                imageVector = ElementCallTheme.icons.minimize,
                contentDescription = stringResource(R.string.element_call_a11y_minimize_call),
                tint = ElementCallTheme.colors.iconPrimary,
            )
        }
        CallOverflowMenu(state = state, modifier = Modifier.align(Alignment.CenterEnd))
        Text(
            text = state.roomName ?: state.spotlightTile?.displayName ?: "",
            style = ElementCallTheme.typography.bodyLgMedium,
            color = ElementCallTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                // Room for the buttons on both sides, so the title is centred on the screen rather
                // than on what is left of it.
                .padding(horizontal = TOP_BAR_BUTTON_ROOM),
        )
    }
}

/**
 * How long the call has run, over the top of the stage.
 *
 * Only in a direct message. The design puts it there because there is nothing else to say about a
 * call with two people in it; a group call has the member count in the spotlight instead.
 */
@Composable
private fun CallDurationLabel(state: ElementCallScreenState, modifier: Modifier = Modifier) {
    val isConnected = state.connection is ElementCallConnection.Connected || state.connection is ElementCallConnection.Degraded
    if (!state.isDm || !isConnected) return
    Text(
        text = rememberCallDuration(state.connectedAtElapsedMs),
        style = ElementCallTheme.typography.bodyMdMedium,
        color = ElementCallTheme.colors.onOverlay,
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(ElementCallTheme.colors.overlayScrim)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * That your screen is going out, and how to stop it.
 *
 * A banner rather than a tile, and deliberately not a preview in the spotlight. `MediaProjection`
 * captures the whole display *including this app*, so a self view of your own screen contains itself:
 * an infinite corridor of call screens, redrawn thirty times a second, costing a decode and a GL
 * surface to tell you nothing. Every desktop and mobile client avoids it for the same reason.
 *
 * What the person sharing actually needs is confirmation and a way out, both of which are cheap and
 * neither of which needs a frame of video. The system's own screen-recording indicator covers the
 * case where they have left the app entirely - which, when sharing a screen, is most of the time.
 */
@Composable
private fun ScreenShareBanner(state: ElementCallScreenState, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = state.isScreenSharing,
        modifier = modifier,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(ElementCallTheme.colors.sharingBanner)
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = ElementCallTheme.icons.shareScreenActive,
                contentDescription = null,
                tint = ElementCallTheme.colors.onOverlay,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(R.string.element_call_sharing_your_screen),
                style = ElementCallTheme.typography.bodySmMedium,
                color = ElementCallTheme.colors.onOverlay,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(
                onClick = { state.eventSink(ElementCallScreenEvent.ToggleScreenShare) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.element_call_action_stop),
                    style = ElementCallTheme.typography.bodySmMedium,
                    color = ElementCallTheme.colors.onOverlay,
                )
            }
        }
    }
}

/**
 * What is happening before there is anyone to show.
 *
 * Distinct from an empty grid on purpose: "connecting" and "a call with nobody in it" look identical
 * if both render as blank, and the first is by far the more common.
 */
@Composable
private fun ConnectingPlaceholder(state: ElementCallScreenState) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = state.connection.label(),
                style = ElementCallTheme.typography.headingMdBold,
                color = ElementCallTheme.colors.textPrimary,
            )
            val failure = state.connection as? ElementCallConnection.Failed
            if (failure != null) {
                Text(
                    text = failure.message,
                    style = ElementCallTheme.typography.bodySmRegular,
                    color = ElementCallTheme.colors.textCritical,
                    modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun CallControlsBar(
    state: ElementCallScreenState,
    /**
     * Held sideways the buttons sit together in the middle, as the design has them, rather than
     * spread across a width that is twice what they need. Upright they spread across the phone.
     */
    isCompact: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Held sideways the bar is the buttons alone, so a drag beside them reaches the strip under it.
            .then(if (isCompact) Modifier.wrapContentWidth(Alignment.CenterHorizontally) else Modifier)
            // A drag that starts on the bar does not scroll the grid it floats over (spec 003 R45):
            // a state that reports every delta consumed leaves nothing for the stage's scrollable.
            .scrollable(rememberScrollableState { it }, Orientation.Vertical)
            .padding(horizontal = 8.dp, vertical = 20.dp),
        horizontalArrangement = if (isCompact) Arrangement.spacedBy(COMPACT_BUTTON_GAP, Alignment.CenterHorizontally) else Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundCallButton(
            onClick = { state.eventSink(ElementCallScreenEvent.ToggleMicrophoneMuted) },
            icon = if (state.isMicrophoneMuted) ElementCallTheme.icons.microphoneOff else ElementCallTheme.icons.microphoneOn,
            contentDescription = stringResource(
                if (state.isMicrophoneMuted) R.string.element_call_a11y_unmute_microphone else R.string.element_call_a11y_mute_microphone
            ),
            isActive = state.isMicrophoneMuted,
        )
        RoundCallButton(
            onClick = { state.eventSink(ElementCallScreenEvent.ToggleCamera) },
            icon = if (state.isCameraEnabled) ElementCallTheme.icons.cameraOn else ElementCallTheme.icons.cameraOff,
            contentDescription = stringResource(
                if (state.isCameraEnabled) R.string.element_call_a11y_turn_camera_off else R.string.element_call_a11y_turn_camera_on
            ),
            // Same reading as the mic: the highlighted button is the one that is switched off.
            isActive = !state.isCameraEnabled,
        )
        AudioDeviceButton(state)
        // Absent rather than disabled when the host has not opted in: there is nothing the user could
        // do to make it work, and a button that never does anything is a bug report.
        if (state.isScreenShareAvailable) {
            RoundCallButton(
                onClick = { state.eventSink(ElementCallScreenEvent.ToggleScreenShare) },
                icon = if (state.isScreenSharing) ElementCallTheme.icons.shareScreenActive else ElementCallTheme.icons.shareScreen,
                contentDescription = stringResource(
                    if (state.isScreenSharing) R.string.element_call_a11y_stop_screen_share else R.string.element_call_a11y_start_screen_share
                ),
                isActive = state.isScreenSharing,
            )
        }
        RoundCallButton(
            onClick = { state.eventSink(ElementCallScreenEvent.HangUp) },
            icon = ElementCallTheme.icons.endCall,
            contentDescription = stringResource(R.string.element_call_a11y_hang_up),
            isActive = false,
            background = ElementCallTheme.colors.hangUp,
            tint = ElementCallTheme.colors.onOverlay,
        )
    }
}

@Composable
private fun AudioDeviceButton(state: ElementCallScreenState) {
    var isPickerVisible by remember { mutableStateOf(false) }
    val selectedType = state.selectedAudioDevice?.type ?: CallAudioDeviceType.EARPIECE
    RoundCallButton(
        // The icon is whatever audio is currently coming out of, so the route is readable without
        // opening anything - which is the question people actually have mid-call.
        onClick = { isPickerVisible = true },
        icon = selectedType.icon(),
        contentDescription = stringResource(R.string.element_call_audio_output_title),
        // Same reading as mic and camera: the highlighted button is the one that is switched off,
        // and the earpiece is "loudspeaker off".
        isActive = selectedType == CallAudioDeviceType.EARPIECE,
        enabled = state.audioDevices.isNotEmpty(),
    )
    if (isPickerVisible) {
        AudioDevicePicker(
            devices = state.audioDevices,
            selectedDevice = state.selectedAudioDevice,
            onSelect = { state.eventSink(ElementCallScreenEvent.SelectAudioDevice(it)) },
            onDismiss = { isPickerVisible = false },
        )
    }
}

@Composable
private fun RoundCallButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    isActive: Boolean,
    enabled: Boolean = true,
    background: Color? = null,
    tint: Color? = null,
) {
    val resolvedBackground = background
        ?: if (isActive) ElementCallTheme.colors.controlActiveBackground else ElementCallTheme.colors.bgSubtleSecondary
    val resolvedTint = tint
        ?: if (isActive) ElementCallTheme.colors.controlActiveContent else ElementCallTheme.colors.iconPrimary
    // A dark button over a dark tile would be only its icon; the ring tells it apart from what it
    // floats over, as the design draws it. The light and the coloured ones stand out on their own.
    val hasRing = background == null && !isActive
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(BUTTON_SIZE)
            .clip(CircleShape)
            .background(resolvedBackground)
            .then(if (hasRing) Modifier.border(1.dp, ElementCallTheme.colors.borderControl, CircleShape) else Modifier),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = resolvedTint,
        )
    }
}

/**
 * As the design has it. The bar holds at most five - microphone, camera, audio output, screen share,
 * hang up; switching camera is on our own tile - and five 52dp circles leave room between them
 * across a 360dp phone.
 */
private val BUTTON_SIZE = 52.dp

/** Between the buttons when they sit together in the middle of a landscape bar. */
private val COMPACT_BUTTON_GAP = 16.dp

/** What the placeholder says about the connection while there is nobody to show. */
@Composable
private fun ElementCallConnection.label(): String = when (this) {
    ElementCallConnection.RequestingPermission,
    ElementCallConnection.Joining,
    ElementCallConnection.ConnectingMedia -> stringResource(R.string.element_call_connecting)
    ElementCallConnection.Connected -> stringResource(R.string.element_call_call_in_progress)
    ElementCallConnection.Degraded -> stringResource(R.string.element_call_connection_unstable)
    is ElementCallConnection.Failed -> stringResource(R.string.element_call_call_failed)
    ElementCallConnection.Ended -> stringResource(R.string.element_call_call_ended)
}

/** The controls bar's height: its vertical padding either side of a button. What the stage keeps its last row clear of. */
private val CONTROLS_HEIGHT = 20.dp + BUTTON_SIZE + 20.dp

/** How long the chrome takes to slide away or back (014 R25). */
private const val CHROME_SLIDE_MS = 250

/** How far the landscape top bar's scrim runs past the bar, so it fades out over the picture rather than ending in an edge. */
private val TOP_SCRIM_EXTENT = 24.dp

/** The top bar's height: an icon button and the bar's padding either side of it. */
private val TOP_BAR_HEIGHT = 56.dp

/** An icon button's width plus the bar's own padding, kept clear on both sides of the title. */
private val TOP_BAR_BUTTON_ROOM = 52.dp

@PreviewsDayNight
@Composable
internal fun ElementCallScreenPreview(@PreviewParameter(ElementCallScreenStatePreviewParam::class) state: ElementCallScreenState) = ElementCallPreview {
    ElementCallScreen(state = state)
}
