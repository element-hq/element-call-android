/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.callnative.impl.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.callnative.impl.NativeCallConnection
import io.element.android.libraries.audio.api.CallAudioDeviceType
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.ButtonSize
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextButton
import io.element.android.libraries.ui.strings.CommonStrings

/**
 * The call, as a product rather than as an instrument.
 *
 * Spotlight above a strip, per the design: one member large - whoever is talking - and everyone else
 * in a grid below. The diagnostics screen this replaces is still reachable from developer settings,
 * because it is what the RTC library feedback was written from.
 */
@Composable
fun CallScreenView(
    state: NativeCallState,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isLandscape = maxWidth > maxHeight
            val tiles = @Composable { tilesModifier: Modifier, pipInsets: PaddingValues ->
                if (state.tiles.isEmpty()) {
                    ConnectingPlaceholder(state)
                } else {
                    CallTileLayout(state = state, modifier = tilesModifier, pipInsets = pipInsets)
                }
            }

            if (isLandscape) {
                // Held sideways there is not enough height to spend two bars' worth of it on chrome -
                // stacked, the top bar and the controls take about two fifths of a phone's landscape
                // height, which is exactly the height the video wanted. So they float over the tiles
                // instead, as the design shows, with a scrim under the controls to keep them legible
                // against whatever happens to be behind them.
                Box(modifier = Modifier.fillMaxSize()) {
                    // The controls float over the tiles here, so the one-to-one thumbnail is told to
                    // keep clear of them - the other person may run under the scrim, we should not.
                    val controlsInset = CONTROLS_HEIGHT + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
                    tiles(Modifier.fillMaxSize(), PaddingValues(bottom = controlsInset))
                    CallTopBar(state, modifier = Modifier.systemBarsPadding())
                    // Stacked so a share that is still running when the call becomes one-to-one does
                    // not draw its banner through the duration.
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .systemBarsPadding()
                            .padding(top = 56.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ScreenShareBanner(state = state)
                        CallDurationLabel(state = state)
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(CONTROLS_SCRIM),
                    ) {
                        CallControlsBar(state, modifier = Modifier.systemBarsPadding())
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding(),
                ) {
                    CallTopBar(state)
                    ScreenShareBanner(state = state, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Box(modifier = Modifier.weight(1f)) {
                        // Nothing floats over the tiles in portrait, so the thumbnail needs no inset.
                        tiles(Modifier.fillMaxSize(), PaddingValues(0.dp))
                        CallDurationLabel(
                            state = state,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 12.dp),
                        )
                    }
                    CallControlsBar(state)
                }
            }
        }
    }
}

/**
 * Who the call is with, centred as the design has it, with the way out of the screen at the start.
 *
 * In a DM the room's name *is* the other person's name, but it arrives asynchronously, so until it
 * does the person in the spotlight stands in for it rather than leaving the bar blank.
 */
@Composable
private fun CallTopBar(state: NativeCallState, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        IconButton(
            onClick = { state.eventSink(NativeCallEvent.Minimize) },
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Icon(
                imageVector = CompoundIcons.Collapse(),
                contentDescription = stringResource(CommonStrings.a11y_minimize_call),
                tint = ElementTheme.colors.iconPrimary,
            )
        }
        Text(
            text = state.roomName ?: state.spotlightParticipant?.displayName ?: "",
            style = ElementTheme.typography.fontBodyLgMedium,
            color = ElementTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                // Room for the button on both sides, so the title is centred on the screen rather
                // than on what is left of it.
                .padding(horizontal = TOP_BAR_BUTTON_ROOM),
        )
    }
}

/**
 * How long the call has run, over the top of the other person's picture.
 *
 * Only in a one-to-one call. The design puts it there because there is nothing else to say about a
 * call with two people in it; a group call has the member count in the spotlight instead.
 */
@Composable
private fun CallDurationLabel(state: NativeCallState, modifier: Modifier = Modifier) {
    val isConnected = state.connection is NativeCallConnection.Connected || state.connection is NativeCallConnection.Degraded
    if (state.layout != CallLayout.OneToOne || !isConnected) return
    Text(
        text = rememberCallDuration(state.connectedAtElapsedMs),
        style = ElementTheme.typography.fontBodyMdMedium,
        color = Color.White,
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(PILL_BACKGROUND)
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
private fun ScreenShareBanner(state: NativeCallState, modifier: Modifier = Modifier) {
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
                .background(SHARING_BANNER_BACKGROUND)
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = CompoundIcons.ShareScreenSolid(),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(CommonStrings.screen_call_sharing_your_screen),
                style = ElementTheme.typography.fontBodySmMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(
                text = stringResource(CommonStrings.action_stop),
                size = ButtonSize.Small,
                onClick = { state.eventSink(NativeCallEvent.ToggleScreenShare) },
            )
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
private fun ConnectingPlaceholder(state: NativeCallState) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = state.connection.label(),
                style = ElementTheme.typography.fontHeadingMdBold,
                color = ElementTheme.colors.textPrimary,
            )
            val failure = state.connection as? NativeCallConnection.Failed
            if (failure != null) {
                Text(
                    text = failure.message,
                    style = ElementTheme.typography.fontBodySmRegular,
                    color = ElementTheme.colors.textCriticalPrimary,
                    modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun CallControlsBar(state: NativeCallState, modifier: Modifier = Modifier) {
    Row(
        // Tighter than it looks like it should be, because six 52dp buttons do not fit across a
        // small phone with room to breathe between them. See BUTTON_SIZE.
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundCallButton(
            onClick = { state.eventSink(NativeCallEvent.ToggleMicrophoneMuted) },
            icon = if (state.isMicrophoneMuted) CompoundIcons.MicOffSolid() else CompoundIcons.MicOnSolid(),
            contentDescription = stringResource(
                if (state.isMicrophoneMuted) CommonStrings.a11y_unmute_microphone else CommonStrings.a11y_mute_microphone
            ),
            isActive = state.isMicrophoneMuted,
        )
        RoundCallButton(
            onClick = { state.eventSink(NativeCallEvent.ToggleCamera) },
            icon = if (state.isCameraEnabled) CompoundIcons.VideoCallSolid() else CompoundIcons.VideoCallOffSolid(),
            contentDescription = stringResource(
                if (state.isCameraEnabled) CommonStrings.a11y_turn_camera_off else CommonStrings.a11y_turn_camera_on
            ),
            // Same reading as the mic: the highlighted button is the one that is switched off.
            isActive = !state.isCameraEnabled,
        )
        AudioDeviceButton(state)
        RoundCallButton(
            onClick = { state.eventSink(NativeCallEvent.ToggleScreenShare) },
            icon = if (state.isScreenSharing) CompoundIcons.ShareScreenSolid() else CompoundIcons.ShareScreen(),
            contentDescription = stringResource(
                if (state.isScreenSharing) CommonStrings.a11y_stop_screen_share else CommonStrings.a11y_start_screen_share
            ),
            isActive = state.isScreenSharing,
        )
        // In a one-to-one call this lives on our thumbnail instead - see CallTileLayout - which is
        // both where the design puts it and one fewer button to fit across the bar.
        if (state.layout == CallLayout.Group) {
            RoundCallButton(
                onClick = { state.eventSink(NativeCallEvent.SwitchCamera) },
                icon = CompoundIcons.SwitchCameraSolid(),
                contentDescription = stringResource(CommonStrings.a11y_switch_camera),
                isActive = false,
                enabled = state.isCameraEnabled,
            )
        }
        RoundCallButton(
            onClick = { state.eventSink(NativeCallEvent.HangUp) },
            icon = CompoundIcons.EndCall(),
            contentDescription = stringResource(CommonStrings.a11y_hang_up),
            isActive = false,
            background = HANG_UP_RED,
            tint = Color.White,
        )
    }
}

@Composable
private fun AudioDeviceButton(state: NativeCallState) {
    var isPickerVisible by remember { mutableStateOf(false) }
    val selectedType = state.selectedAudioDevice?.type ?: CallAudioDeviceType.EARPIECE
    RoundCallButton(
        // The icon is whatever audio is currently coming out of, so the route is readable without
        // opening anything - which is the question people actually have mid-call.
        onClick = { isPickerVisible = true },
        icon = selectedType.icon(),
        contentDescription = stringResource(CommonStrings.screen_call_audio_output_title),
        // Same reading as mic and camera: the highlighted button is the one that is switched off,
        // and the earpiece is "loudspeaker off".
        isActive = selectedType == CallAudioDeviceType.EARPIECE,
        enabled = state.audioDevices.isNotEmpty(),
    )
    if (isPickerVisible) {
        AudioDevicePicker(
            devices = state.audioDevices,
            selectedDevice = state.selectedAudioDevice,
            onSelect = { state.eventSink(NativeCallEvent.SelectAudioDevice(it)) },
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
        ?: if (isActive) Color.White else ElementTheme.colors.bgSubtleSecondary
    val resolvedTint = tint
        ?: if (isActive) Color.Black else ElementTheme.colors.iconPrimary
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(BUTTON_SIZE)
            .clip(CircleShape)
            .background(resolvedBackground),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = resolvedTint,
        )
    }
}

/**
 * 48dp rather than the 52 this started at: screen share made six buttons in a group call, and six
 * 52dp circles come to 312dp, which leaves nothing between them on a 360dp phone. Still at the 48dp
 * minimum touch target, so nothing is harder to hit - only closer together. A one-to-one call has
 * five and could afford more, but the two bars should not be different sizes.
 */
private val BUTTON_SIZE = 48.dp

/** The controls bar's height: its vertical padding either side of a button. Read by the landscape thumbnail inset. */
private val CONTROLS_HEIGHT = 20.dp + BUTTON_SIZE + 20.dp

/** An icon button's width plus the bar's own padding, kept clear on both sides of the title. */
private val TOP_BAR_BUTTON_ROOM = 52.dp

private val HANG_UP_RED = Color(0xFFE5484D)

/** Fixed rather than themed: it sits over video, which is not a themed surface. */
private val PILL_BACKGROUND = Color(0xCC15191E)

/** Behind the floating landscape controls, so they stay readable over a bright tile. */
private val CONTROLS_SCRIM = Brush.verticalGradient(listOf(Color.Transparent, Color(0xB315191E)))

/** Green rather than the usual pill grey: this is a state the user should notice they are in. */
private val SHARING_BANNER_BACKGROUND = Color(0xFF0F7B6C)

@PreviewsDayNight
@Composable
internal fun CallScreenViewPreview(@PreviewParameter(NativeCallStatePreviewParam::class) state: NativeCallState) = ElementPreview {
    CallScreenView(state = state)
}
