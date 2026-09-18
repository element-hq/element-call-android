/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import io.element.android.call.ui.preview.ElementCallPreview
import io.element.android.call.ui.preview.PreviewsDayNight
import io.element.android.call.ui.theme.ElementCallAvatar
import io.element.android.call.ui.theme.ElementCallAvatarSize
import io.element.android.call.ui.theme.ElementCallTheme
import io.element.android.call.ui.video.CallVideoRenderer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The call as a floating window: one tile, no chrome.
 *
 * Everything the full screen has is dropped rather than shrunk. At the size Android gives a PiP
 * window, control buttons are below the minimum touch target and a strip of other participants is a
 * row of unrecognisable thumbnails - and the system already provides the two actions that matter, an
 * expand tap and a dismiss, drawn over the window itself.
 *
 * Which tile: whoever is spotlighted, which follows the same rule as the full screen and so means a
 * shared screen if there is one and the current speaker otherwise. Both are the right answer to
 * "there is only room for one thing, what is it".
 *
 * [videoFrames] is how to reach a member's stream, passed rather than read from state so this can
 * open exactly one - collecting is what makes the core decode, and PiP should decode one tile's worth
 * rather than the whole call's.
 */
@Composable
fun ElementCallPictureInPictureView(
    call: ElementCallSnapshot,
    videoFrames: (memberId: String, kind: MatrixRtcStreamKind) -> Flow<MatrixRtcVideoFrame>,
    modifier: Modifier = Modifier,
) {
    val participants = call.participants
    // The same order of preference the call screen uses, reduced to a single winner.
    val screenSharer = participants.firstOrNull { participant ->
        !participant.isLocal && participant.streams.any { it.kind == MatrixRtcStreamKind.SCREEN_SHARE && !it.isMuted }
    }
    val spotlit = participants.firstOrNull { it.memberId == call.spotlightMemberId && !it.isLocal }
        ?: participants.firstOrNull { !it.isLocal }

    val memberId = screenSharer?.memberId ?: spotlit?.memberId
    val kind = if (screenSharer != null) MatrixRtcStreamKind.SCREEN_SHARE else MatrixRtcStreamKind.CAMERA
    val hasVideo = when {
        screenSharer != null -> true
        else -> spotlit?.streams?.any { it.kind == MatrixRtcStreamKind.CAMERA && !it.isMuted } == true
    }

    Surface(modifier = modifier.fillMaxSize(), color = Color.Black) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                memberId != null && hasVideo -> CallVideoRenderer(
                    frames = videoFrames(memberId, kind),
                    // Never our own camera here - the spotlight is never us - so never mirrored.
                    isMirrored = false,
                    modifier = Modifier.fillMaxSize(),
                )
                spotlit != null -> ElementCallAvatar(
                    userId = spotlit.userId,
                    roomMember = call.roomMembers[spotlit.userId],
                    size = ElementCallAvatarSize.Spotlight,
                )
                // Alone in the call, or not connected yet. Says so rather than showing a black
                // rectangle, which is indistinguishable from a broken window.
                else -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = ElementCallTheme.icons.cameraOn,
                        contentDescription = stringResource(R.string.element_call_call_in_progress),
                        tint = ElementCallTheme.colors.iconSecondary,
                    )
                }
            }

            if (call.isScreenSharing) {
                Text(
                    text = stringResource(R.string.element_call_sharing_your_screen),
                    style = ElementCallTheme.typography.bodyXsMedium,
                    color = ElementCallTheme.colors.onOverlay,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

// At the size Android gives a picture-in-picture window, which is the only size this is ever drawn at.
@PreviewsDayNight
@Composable
internal fun ElementCallPictureInPictureViewPreview(
    @PreviewParameter(MinimizedElementCallSnapshotPreviewParam::class) call: ElementCallSnapshot,
) = ElementCallPreview {
    ElementCallPictureInPictureView(
        call = call,
        videoFrames = { _, _ -> emptyFlow() },
        modifier = Modifier.size(width = 240.dp, height = 135.dp),
    )
}
