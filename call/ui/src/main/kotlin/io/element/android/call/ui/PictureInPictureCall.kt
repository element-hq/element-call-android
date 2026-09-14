/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.callnative.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.callnative.impl.NativeCallSnapshot
import io.element.android.libraries.designsystem.components.avatar.Avatar
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.components.avatar.AvatarType
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.ui.model.getAvatarData
import io.element.android.libraries.matrixrtc.api.MatrixRtcStreamKind
import io.element.android.libraries.matrixrtc.api.MatrixRtcVideoFrame
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.coroutines.flow.Flow

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
 * @param videoFrames how to reach a member's stream, passed rather than read from state so this can
 * open exactly one - collecting is what makes the core decode, and PiP should decode one tile's worth
 * rather than the whole call's.
 */
@Composable
fun PictureInPictureCall(
    call: NativeCallSnapshot,
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
                spotlit != null -> {
                    val roomMember = call.roomMembers[spotlit.userId]
                    Avatar(
                        avatarData = roomMember?.getAvatarData(AvatarSize.CallSpotlight)
                            ?: AvatarData(id = spotlit.userId.value, name = null, url = null, size = AvatarSize.CallSpotlight),
                        avatarType = AvatarType.User,
                    )
                }
                // Alone in the call, or not connected yet. Says so rather than showing a black
                // rectangle, which is indistinguishable from a broken window.
                else -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = CompoundIcons.VideoCallSolid(),
                        contentDescription = stringResource(CommonStrings.common_call_in_progress),
                        tint = ElementTheme.colors.iconSecondary,
                    )
                }
            }

            if (call.isScreenSharing) {
                Text(
                    text = stringResource(CommonStrings.screen_call_sharing_your_screen),
                    style = ElementTheme.typography.fontBodyXsMedium,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}
