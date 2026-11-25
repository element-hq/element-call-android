/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.element.android.call.api.ElementCallLobbyJoinLabel
import io.element.android.call.ui.preview.ElementCallPreview
import io.element.android.call.ui.preview.PreviewsDayNight
import io.element.android.call.ui.theme.ElementCallTheme

/*
 * Where the lobby's pieces sit is design's to settle (feature-hq 045 Q5, Q8): the count, the call-ended notice, the
 * refused microphone's message and the loading Join are placed here and in [JOIN_SECTION_HEIGHT] alone.
 */

/** Whether [CallLobbySummary] is drawn: while somebody is in the call. */
internal val ElementCallLobbyState?.showsSummary: Boolean
    get() = this is ElementCallLobbyState.Ready && memberCount > 0

/** The summary's height, kept for the whole lobby so the preview under it never moves. */
internal val LOBBY_SUMMARY_HEIGHT = 64.dp

/** Under the control bar: the Join line's height, which the preview frame stays clear of. Fixed, so the frame never moves (R9). */
internal val JOIN_SECTION_HEIGHT = 72.dp

/**
 * Over the preview, in its frame, so the bar under it never moves: the loading indicator (R59), the call-ended notice (R57),
 * the refused microphone's message and button (R27), and the refused camera's reason with the way to settings (R28).
 */
@Composable
internal fun CallLobbyPreviewOverlay(lobby: ElementCallLobbyState, eventSink: (ElementCallScreenEvent) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        when (lobby) {
            ElementCallLobbyState.Loading -> CircularProgressIndicator(
                color = ElementCallTheme.colors.iconPrimary,
                strokeWidth = 3.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .size(28.dp)
                    // The Join button says it is loading; one announcement is enough.
                    .clearAndSetSemantics {},
            )
            is ElementCallLobbyState.Ready -> Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (lobby.isCallEndedNoticeShown) CallEndedNotice()
                lobby.microphoneAction?.let { action -> MicrophoneRefused(action = action, eventSink = eventSink) }
                if (lobby.camera == ElementCallLobbyCamera.NOT_ALLOWED) CameraNotAllowed(onOpenSettings = { eventSink(ElementCallScreenEvent.OpenAppSettings) })
            }
        }
    }
}

@Composable
private fun CameraNotAllowed(onOpenSettings: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.element_call_permission_camera_title),
            style = ElementCallTheme.typography.bodyLgMedium,
            color = ElementCallTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.element_call_permission_camera_description),
            style = ElementCallTheme.typography.bodyMdRegular,
            color = ElementCallTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        LobbyButton(
            text = stringResource(R.string.element_call_action_open_settings),
            isPrimary = false,
            onClick = onOpenSettings,
            modifier = Modifier.testTag(ElementCallTestTags.LOBBY_CAMERA_SETTINGS),
        )
    }
}

/** Under the control bar: Start or Join, centred. Join is where a screen reader starts (R35). */
@Composable
internal fun CallLobbyJoinSection(lobby: ElementCallLobbyState, eventSink: (ElementCallScreenEvent) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(JOIN_SECTION_HEIGHT)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        JoinButton(lobby = lobby, onJoin = { eventSink(ElementCallScreenEvent.Join) }, modifier = Modifier.fillMaxWidth())
    }
}

/** Under the top bar, while somebody is in the call: that it is in progress, and how many (R41). */
@Composable
internal fun CallLobbySummary(lobby: ElementCallLobbyState?, isAudioCall: Boolean, modifier: Modifier = Modifier) {
    if (!lobby.showsSummary) return
    val ready = lobby as ElementCallLobbyState.Ready
    Column(
        modifier = modifier.height(LOBBY_SUMMARY_HEIGHT),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                imageVector = if (isAudioCall) ElementCallTheme.icons.voiceCall else ElementCallTheme.icons.cameraOn,
                contentDescription = null,
                tint = ElementCallTheme.colors.iconSecondary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(R.string.element_call_lobby_call_in_progress),
                style = ElementCallTheme.typography.bodyLgMedium,
                color = ElementCallTheme.colors.textPrimary,
            )
        }
        // The core counts the call; it does not say who is in it before Join (R43), so no faces here.
        val count = pluralStringResource(R.plurals.element_call_lobby_member_count, ready.memberCount, ready.memberCount)
        Row(
            modifier = Modifier.clearAndSetSemantics { contentDescription = count },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = ElementCallTheme.icons.participants,
                contentDescription = null,
                tint = ElementCallTheme.colors.iconSecondary,
                modifier = Modifier.size(18.dp),
            )
            Text(text = ready.memberCount.toString(), style = ElementCallTheme.typography.bodyMdMedium, color = ElementCallTheme.colors.textPrimary)
        }
    }
}

/** Said when it appears without taking focus off whatever the user is on (R58). */
@Composable
private fun CallEndedNotice() {
    Text(
        text = stringResource(R.string.element_call_lobby_call_ended),
        style = ElementCallTheme.typography.bodyMdMedium,
        color = ElementCallTheme.colors.onOverlay,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(ElementCallTheme.colors.overlayScrim)
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(ElementCallTestTags.LOBBY_CALL_ENDED),
    )
}

/** 042's title, the lobby's description, and a button that asks again where the system still does (R27). */
@Composable
private fun MicrophoneRefused(action: ElementCallLobbyMicrophoneAction, eventSink: (ElementCallScreenEvent) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.element_call_permission_microphone_title),
            style = ElementCallTheme.typography.bodyLgMedium,
            color = ElementCallTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.element_call_lobby_microphone_description),
            style = ElementCallTheme.typography.bodyMdRegular,
            color = ElementCallTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        LobbyButton(
            text = stringResource(
                when (action) {
                    ElementCallLobbyMicrophoneAction.ALLOW -> R.string.element_call_lobby_microphone_allow
                    ElementCallLobbyMicrophoneAction.OPEN_SETTINGS -> R.string.element_call_action_open_settings
                }
            ),
            isPrimary = false,
            onClick = {
                when (action) {
                    ElementCallLobbyMicrophoneAction.ALLOW -> eventSink(ElementCallScreenEvent.RequestMicrophonePermission)
                    ElementCallLobbyMicrophoneAction.OPEN_SETTINGS -> eventSink(ElementCallScreenEvent.OpenAppSettings)
                }
            },
            modifier = Modifier.testTag(ElementCallTestTags.LOBBY_MICROPHONE_ACTION),
        )
    }
}

/**
 * One node from the first frame, loading or not, so a screen reader that starts on it hears it change in place (R35,
 * R59, contract C20). Disabled, it says why (R51).
 */
@Composable
private fun JoinButton(lobby: ElementCallLobbyState, onJoin: () -> Unit, modifier: Modifier = Modifier) {
    val ready = lobby as? ElementCallLobbyState.Ready
    val isEnabled = ready != null && ready.joinUnavailableReason == null
    val label = ready?.joinLabel?.let { stringResource(it.text) }
    val description = label ?: stringResource(R.string.element_call_lobby_loading)
    // Disabled, the reason (R51, C26); enabled, the count with the label (R35, R41).
    val state = when {
        ready == null -> stringResource(R.string.element_call_lobby_loading)
        ready.joinUnavailableReason == ElementCallLobbyJoinBlock.MICROPHONE_REFUSED -> stringResource(R.string.element_call_lobby_microphone_description)
        ready.memberCount > 0 -> pluralStringResource(R.plurals.element_call_lobby_member_count, ready.memberCount, ready.memberCount)
        else -> null
    }
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(ElementCallTheme.colors.bgActionPrimaryRest)
            .alpha(if (isEnabled) 1f else DISABLED_ALPHA)
            .clickable(enabled = isEnabled, onClick = onJoin)
            .clearAndSetSemantics {
                testTag = ElementCallTestTags.LOBBY_JOIN
                contentDescription = description
                role = Role.Button
                // First of the window for a screen reader (R35).
                traversalIndex = -1f
                // The description changes in place as loading ends; said without moving focus (C20).
                liveRegion = LiveRegionMode.Polite
                if (!isEnabled) disabled()
                state?.let { stateDescription = it }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (label == null) {
            CircularProgressIndicator(color = ElementCallTheme.colors.iconOnSolidPrimary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        } else {
            Text(text = label, style = ElementCallTheme.typography.bodyLgMedium, color = ElementCallTheme.colors.iconOnSolidPrimary)
        }
    }
}

@Composable
private fun LobbyButton(text: String, isPrimary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(if (isPrimary) ElementCallTheme.colors.bgActionPrimaryRest else ElementCallTheme.colors.bgSubtleSecondary)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = ElementCallTheme.typography.bodyMdMedium,
            color = if (isPrimary) ElementCallTheme.colors.iconOnSolidPrimary else ElementCallTheme.colors.textPrimary,
        )
    }
}

internal val ElementCallLobbyJoinLabel.text: Int
    get() = when (this) {
        ElementCallLobbyJoinLabel.START -> R.string.element_call_lobby_start
        ElementCallLobbyJoinLabel.JOIN -> R.string.element_call_lobby_join
        ElementCallLobbyJoinLabel.JOIN_ANYWAY -> R.string.element_call_lobby_join_anyway
    }

private const val DISABLED_ALPHA = 0.4f

@PreviewsDayNight
@Composable
internal fun ElementCallLobbyScreenPreview(@PreviewParameter(
    LobbyElementCallScreenStatePreviewParam::class
) state: ElementCallScreenState) = ElementCallPreview {
    ElementCallScreen(state = state)
}
