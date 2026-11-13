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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallFailure
import io.element.android.call.api.rtc.MatrixRtcConnectionLossReason
import io.element.android.call.api.rtc.MatrixRtcMembershipFormat
import io.element.android.call.ui.preview.ElementCallPreview
import io.element.android.call.ui.preview.PreviewsDayNight
import io.element.android.call.ui.theme.ElementCallTheme

/**
 * What a failed call shows until the user closes it (feature-hq 034 R27): an icon, a title, why, and the way out, the whole
 * screen and nothing else, as Element Call web does. Closing is the hang-up that ends the call.
 * A refused microphone also offers the app's settings first (042 R7), which ends the call too: the user starts it again.
 * A failure the MSC membership format may have caused, judged by the format the call joined with, says so and offers Room
 * state first (043 R18, R18a).
 */
@Composable
internal fun CallFailure(
    failed: ElementCallConnection.Failed,
    joiningFormat: MatrixRtcMembershipFormat?,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onUseRoomState: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isMicrophoneRefused = failed.failure == ElementCallFailure.MicrophoneRefused
    val offersRoomState = failed.failure.mayBeMembershipFormat(joiningFormat)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ElementCallTheme.colors.bgCanvas)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(ElementCallTheme.colors.bgSubtleSecondary, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isMicrophoneRefused) ElementCallTheme.icons.microphoneOff else ElementCallTheme.icons.error,
                    contentDescription = null,
                    tint = ElementCallTheme.colors.iconPrimary,
                    modifier = Modifier.size(32.dp),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = failed.failure.title(),
                    style = ElementCallTheme.typography.headingMdBold,
                    color = ElementCallTheme.colors.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                failed.failure.sentences(offersRoomState).forEach { sentence ->
                    Text(
                        text = sentence,
                        style = ElementCallTheme.typography.bodyLgMedium,
                        color = ElementCallTheme.colors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            when {
                isMicrophoneRefused -> {
                    FailureButton(text = stringResource(R.string.element_call_action_open_settings), isPrimary = true, onClick = onOpenSettings)
                    FailureButton(text = stringResource(R.string.element_call_action_close), isPrimary = false, onClick = onClose)
                }
                offersRoomState -> {
                    FailureButton(
                        text = stringResource(R.string.element_call_failure_use_room_state),
                        isPrimary = true,
                        onClick = onUseRoomState,
                        modifier = Modifier.testTag(ElementCallTestTags.FAILURE_USE_ROOM_STATE),
                    )
                    FailureButton(text = stringResource(R.string.element_call_action_close), isPrimary = false, onClick = onClose)
                }
                else -> FailureButton(text = stringResource(R.string.element_call_action_close), isPrimary = true, onClick = onClose)
            }
        }
    }
}

@Composable
private fun FailureButton(text: String, isPrimary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .then(if (isPrimary) Modifier.background(ElementCallTheme.colors.bgActionPrimaryRest) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = ElementCallTheme.typography.bodyLgMedium,
            color = if (isPrimary) ElementCallTheme.colors.iconOnSolidPrimary else ElementCallTheme.colors.textPrimary,
        )
    }
}

/** The failure screen's title: one per kind of failure. Also the minimized bar's line. */
@Composable
internal fun ElementCallFailure.title(): String = stringResource(
    when (this) {
        is ElementCallFailure.ConnectionLost -> when (reason) {
            MatrixRtcConnectionLossReason.BUDGET -> R.string.element_call_failure_connection_lost_title
            MatrixRtcConnectionLossReason.REFUSED,
            MatrixRtcConnectionLossReason.NEVER_CONNECTED,
            MatrixRtcConnectionLossReason.TOKEN_ENDPOINT_UNSUPPORTED -> R.string.element_call_failure_connection_failed_title
        }
        ElementCallFailure.JoinTimedOut,
        ElementCallFailure.JoinRefused -> R.string.element_call_failure_join_failed_title
        ElementCallFailure.MicrophoneRefused -> R.string.element_call_permission_microphone_title
        is ElementCallFailure.Other -> R.string.element_call_call_failed
    }
)

/**
 * Whether the membership format may be why the call failed (feature-hq 043 R18): the call joined in MSC, and either the join
 * failed, or the media connection right after it was lost because the homeserver hands out no MSC4195 token (038 R44).
 * Judged by the failure and the format alone, never by error codes, which differ from one homeserver to another (C4).
 * A join failure in this library is any failure before media connects: the room open, the join and the media connection
 * all arrive as [ElementCallFailure.JoinRefused], [ElementCallFailure.JoinTimedOut] or [ElementCallFailure.Other].
 */
internal fun ElementCallFailure.mayBeMembershipFormat(joiningFormat: MatrixRtcMembershipFormat?): Boolean {
    if (joiningFormat != MatrixRtcMembershipFormat.MSC) return false
    return when (this) {
        ElementCallFailure.JoinRefused,
        is ElementCallFailure.Other -> true
        ElementCallFailure.JoinTimedOut -> TIMED_OUT_JOIN_MAY_BE_MEMBERSHIP_FORMAT
        is ElementCallFailure.ConnectionLost -> reason == MatrixRtcConnectionLossReason.TOKEN_ENDPOINT_UNSUPPORTED
        ElementCallFailure.MicrophoneRefused -> false
    }
}

/**
 * Whether a join that timed out names the format. iOS leaves it out, as a slow homeserver rather than a missing piece; the
 * spec is silent (feature-hq 043 open-questions Q1).
 */
private const val TIMED_OUT_JOIN_MAY_BE_MEMBERSHIP_FORMAT = true

/**
 * Whether a join failure's own sentence is kept above the format's. iOS shows the format's alone; the spec is silent
 * (feature-hq 043 open-questions Q2). The lost token endpoint never keeps its own, which blames the media server (R18).
 */
private const val JOIN_FAILURE_KEEPS_ITS_SENTENCE_BESIDE_THE_FORMAT = true

/** What the screen says beneath the title, one paragraph each: the failure's sentence, the format's, or both. */
@Composable
private fun ElementCallFailure.sentences(mayBeMembershipFormat: Boolean): List<String> {
    if (!mayBeMembershipFormat) return listOf(text())
    val format = stringResource(R.string.element_call_failure_join_membership_format)
    val keepsOwn = JOIN_FAILURE_KEEPS_ITS_SENTENCE_BESIDE_THE_FORMAT && this !is ElementCallFailure.ConnectionLost
    return if (keepsOwn) listOf(text(), format) else listOf(format)
}

/** The sentence beneath the title. */
@Composable
internal fun ElementCallFailure.text(): String = when (this) {
    is ElementCallFailure.ConnectionLost -> stringResource(
        when (reason) {
            MatrixRtcConnectionLossReason.BUDGET -> R.string.element_call_failure_connection_lost
            // No string of its own yet (Localazy): the server not offering tokens reads as a refusal.
            MatrixRtcConnectionLossReason.REFUSED,
            MatrixRtcConnectionLossReason.TOKEN_ENDPOINT_UNSUPPORTED -> R.string.element_call_failure_connection_refused
            MatrixRtcConnectionLossReason.NEVER_CONNECTED -> R.string.element_call_failure_connection_never_established
        }
    )
    ElementCallFailure.JoinTimedOut -> stringResource(R.string.element_call_failure_join_timed_out)
    ElementCallFailure.JoinRefused -> stringResource(R.string.element_call_failure_join_refused)
    ElementCallFailure.MicrophoneRefused -> stringResource(R.string.element_call_permission_microphone_description)
    is ElementCallFailure.Other -> message
}

internal class ElementCallConnectionFailedPreviewParam : PreviewParameterProvider<ElementCallConnection.Failed> {
    override val values = sequenceOf(
        ElementCallFailure.ConnectionLost(MatrixRtcConnectionLossReason.BUDGET),
        ElementCallFailure.ConnectionLost(MatrixRtcConnectionLossReason.NEVER_CONNECTED),
        ElementCallFailure.JoinRefused,
        ElementCallFailure.Other("Homeserver offers no LiveKit transport"),
        ElementCallFailure.MicrophoneRefused,
    ).map(ElementCallConnection::Failed)
}

@PreviewsDayNight
@Composable
internal fun CallFailurePreview(@PreviewParameter(ElementCallConnectionFailedPreviewParam::class) failed: ElementCallConnection.Failed) = ElementCallPreview {
    CallFailure(failed = failed, joiningFormat = MatrixRtcMembershipFormat.ROOM_STATE, onClose = {}, onOpenSettings = {}, onUseRoomState = {})
}

internal class MscElementCallConnectionFailedPreviewParam : PreviewParameterProvider<ElementCallConnection.Failed> {
    override val values = sequenceOf(
        ElementCallFailure.JoinRefused,
        ElementCallFailure.ConnectionLost(MatrixRtcConnectionLossReason.TOKEN_ENDPOINT_UNSUPPORTED),
    ).map(ElementCallConnection::Failed)
}

/** A failure in MSC: the format named, and Room state offered (043 R18, R18a). */
@PreviewsDayNight
@Composable
internal fun MscCallFailurePreview(@PreviewParameter(
    MscElementCallConnectionFailedPreviewParam::class
) failed: ElementCallConnection.Failed) = ElementCallPreview {
    CallFailure(failed = failed, joiningFormat = MatrixRtcMembershipFormat.MSC, onClose = {}, onOpenSettings = {}, onUseRoomState = {})
}
