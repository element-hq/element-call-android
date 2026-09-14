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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.ui.preview.ElementCallPreview
import io.element.android.call.ui.preview.PreviewsDayNight
import io.element.android.call.ui.theme.ElementCallTheme

/**
 * The call, docked above the app while the user gets on with something else.
 *
 * This is the shape the Element Call WebView could never take. A WebView call is a fullscreen thing
 * in its own task, so "shrink to a strip above the room header and keep the audio running" is not a
 * layout problem there, it is an architectural one - which is why the minimized design was never
 * built. Here it is only a different rendering of [ElementCallSnapshot].
 *
 * Always dark, matching the call screen it minimizes from, regardless of the app's theme.
 */
@Composable
fun ElementCallMinimizedBar(
    call: ElementCallSnapshot,
    onToggleMicrophone: () -> Unit,
    onHangUp: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = ElementCallTheme.colors.barBackground,
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .statusBarsPadding()
                .fillMaxWidth()
                .height(MINIMIZED_CALL_BAR_HEIGHT)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularCallButton(
                onClick = onToggleMicrophone,
                background = Color.White,
                contentDescription = stringResource(
                    if (call.isMicrophoneMuted) R.string.element_call_a11y_unmute_microphone else R.string.element_call_a11y_mute_microphone
                ),
            ) {
                Icon(
                    imageVector = if (call.isMicrophoneMuted) ElementCallTheme.icons.microphoneOff else ElementCallTheme.icons.microphoneOn,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(20.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = call.roomName.orEmpty(),
                    style = ElementCallTheme.typography.bodyMdMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Sharing a screen is the one thing about a call you can forget you are doing,
                    // and minimizing is how you get there - you shrink the call precisely so you can
                    // go and show something. The full banner does not fit in a 56dp strip, so the
                    // icon carries it next to the duration.
                    if (call.isScreenSharing) {
                        Icon(
                            imageVector = ElementCallTheme.icons.shareScreenActive,
                            contentDescription = stringResource(R.string.element_call_sharing_your_screen),
                            tint = ElementCallTheme.colors.sharingAccent,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Text(
                        text = call.subtitle(),
                        style = ElementCallTheme.typography.bodySmRegular,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            CircularCallButton(
                onClick = onHangUp,
                background = ElementCallTheme.colors.hangUp,
                contentDescription = stringResource(R.string.element_call_a11y_hang_up),
            ) {
                Icon(
                    imageVector = ElementCallTheme.icons.endCall,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun CircularCallButton(
    onClick: () -> Unit,
    background: Color,
    contentDescription: String,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/**
 * The line under the room name: how the call is doing, or how long it has been running.
 *
 * A duration is only shown once there is one to show. Before media connects the interesting thing is
 * the state, and a timer started at zero would claim a call was up while it was still joining.
 */
@Composable
private fun ElementCallSnapshot.subtitle(): String = when (val connection = connection) {
    ElementCallConnection.RequestingPermission,
    ElementCallConnection.Joining,
    ElementCallConnection.ConnectingMedia -> stringResource(R.string.element_call_connecting)
    ElementCallConnection.Degraded -> stringResource(R.string.element_call_connection_unstable)
    is ElementCallConnection.Failed -> connection.message
    ElementCallConnection.Ended -> stringResource(R.string.element_call_call_ended)
    ElementCallConnection.Connected -> rememberCallDuration(connectedAtElapsedMs)
}

/** Read by the host so content below can consume exactly the space the bar takes. */
internal val MINIMIZED_CALL_BAR_HEIGHT = 56.dp

@PreviewsDayNight
@Composable
internal fun ElementCallMinimizedBarPreview(@PreviewParameter(ElementCallSnapshotPreviewParam::class) call: ElementCallSnapshot) = ElementCallPreview {
    ElementCallMinimizedBar(
        call = call,
        onToggleMicrophone = {},
        onHangUp = {},
        onClick = {},
    )
}
