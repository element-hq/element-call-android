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
import androidx.compose.material3.Surface
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
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.call.impl.NativeCallConnection
import io.element.android.call.impl.NativeCallSnapshot
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.ui.strings.CommonStrings

/**
 * The call, docked above the app while the user gets on with something else.
 *
 * This is the shape the Element Call WebView could never take. A WebView call is a fullscreen thing
 * in its own task, so "shrink to a strip above the room header and keep the audio running" is not a
 * layout problem there, it is an architectural one - which is why the minimized design was never
 * built. Here it is only a different rendering of [NativeCallSnapshot].
 *
 * Always dark, matching the call screen it minimizes from, regardless of the app's theme.
 */
@Composable
fun MinimizedCallBar(
    call: NativeCallSnapshot,
    onToggleMicrophone: () -> Unit,
    onHangUp: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = BAR_BACKGROUND,
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
                    if (call.isMicrophoneMuted) CommonStrings.a11y_unmute_microphone else CommonStrings.a11y_mute_microphone
                ),
            ) {
                Icon(
                    imageVector = if (call.isMicrophoneMuted) CompoundIcons.MicOffSolid() else CompoundIcons.MicOnSolid(),
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
                    style = ElementTheme.typography.fontBodyMdMedium,
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
                            imageVector = CompoundIcons.ShareScreenSolid(),
                            contentDescription = stringResource(CommonStrings.screen_call_sharing_your_screen),
                            tint = SHARING_GREEN,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Text(
                        text = call.subtitle(),
                        style = ElementTheme.typography.fontBodySmRegular,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            CircularCallButton(
                onClick = onHangUp,
                background = HANG_UP_RED,
                contentDescription = stringResource(CommonStrings.a11y_hang_up),
            ) {
                Icon(
                    imageVector = CompoundIcons.EndCall(),
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
private fun NativeCallSnapshot.subtitle(): String = when (connection) {
    NativeCallConnection.RequestingPermission,
    NativeCallConnection.Joining,
    NativeCallConnection.ConnectingMedia -> stringResource(CommonStrings.common_connecting)
    NativeCallConnection.Degraded -> stringResource(CommonStrings.common_connection_unstable)
    is NativeCallConnection.Failed -> connection.message
    NativeCallConnection.Ended -> stringResource(CommonStrings.common_call_ended)
    NativeCallConnection.Connected -> rememberCallDuration(connectedAtElapsedMs)
}

/** Read by the host so content below can consume exactly the space the bar takes. */
internal val MINIMIZED_CALL_BAR_HEIGHT = 56.dp

/** Fixed rather than themed: the bar is the call, and the call screen is always dark. */
private val BAR_BACKGROUND = Color(0xFF15191E)
private val HANG_UP_RED = Color(0xFFE5484D)

/** Matches the sharing banner on the call screen, so the two read as the same state. */
private val SHARING_GREEN = Color(0xFF25B39A)

@PreviewsDayNight
@Composable
internal fun MinimizedCallBarPreview(@PreviewParameter(NativeCallSnapshotPreviewParam::class) call: NativeCallSnapshot) = ElementPreview {
    MinimizedCallBar(
        call = call,
        onToggleMicrophone = {},
        onHangUp = {},
        onClick = {},
    )
}
