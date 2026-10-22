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
import io.element.android.call.ui.preview.ElementCallPreview
import io.element.android.call.ui.preview.PreviewsDayNight
import io.element.android.call.ui.theme.ElementCallTheme

/**
 * What a failed call shows until the user closes it (feature-hq 034 R27): an icon, a title, why, and the way out, the whole
 * screen and nothing else, as Element Call web does. Closing is the hang-up that ends the call.
 */
@Composable
internal fun CallFailure(
    failed: ElementCallConnection.Failed,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                    imageVector = ElementCallTheme.icons.error,
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
                Text(
                    text = failed.failure.text(),
                    style = ElementCallTheme.typography.bodyLgMedium,
                    color = ElementCallTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .background(ElementCallTheme.colors.bgActionPrimaryRest)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.element_call_action_close),
                    style = ElementCallTheme.typography.bodyLgMedium,
                    color = ElementCallTheme.colors.iconOnSolidPrimary,
                )
            }
        }
    }
}

/** The failure screen's title: one per kind of failure. Also the minimized bar's line. */
@Composable
internal fun ElementCallFailure.title(): String = stringResource(
    when (this) {
        is ElementCallFailure.ConnectionLost -> when (reason) {
            MatrixRtcConnectionLossReason.BUDGET -> R.string.element_call_failure_connection_lost_title
            MatrixRtcConnectionLossReason.REFUSED,
            MatrixRtcConnectionLossReason.NEVER_CONNECTED -> R.string.element_call_failure_connection_failed_title
        }
        ElementCallFailure.JoinTimedOut,
        ElementCallFailure.JoinRefused -> R.string.element_call_failure_join_failed_title
        is ElementCallFailure.Other -> R.string.element_call_call_failed
    }
)

/** The sentence beneath the title. */
@Composable
internal fun ElementCallFailure.text(): String = when (this) {
    is ElementCallFailure.ConnectionLost -> stringResource(
        when (reason) {
            MatrixRtcConnectionLossReason.BUDGET -> R.string.element_call_failure_connection_lost
            MatrixRtcConnectionLossReason.REFUSED -> R.string.element_call_failure_connection_refused
            MatrixRtcConnectionLossReason.NEVER_CONNECTED -> R.string.element_call_failure_connection_never_established
        }
    )
    ElementCallFailure.JoinTimedOut -> stringResource(R.string.element_call_failure_join_timed_out)
    ElementCallFailure.JoinRefused -> stringResource(R.string.element_call_failure_join_refused)
    is ElementCallFailure.Other -> message
}

internal class ElementCallConnectionFailedPreviewParam : PreviewParameterProvider<ElementCallConnection.Failed> {
    override val values = sequenceOf(
        ElementCallFailure.ConnectionLost(MatrixRtcConnectionLossReason.BUDGET),
        ElementCallFailure.ConnectionLost(MatrixRtcConnectionLossReason.NEVER_CONNECTED),
        ElementCallFailure.JoinRefused,
        ElementCallFailure.Other("Homeserver offers no LiveKit transport"),
    ).map(ElementCallConnection::Failed)
}

@PreviewsDayNight
@Composable
internal fun CallFailurePreview(@PreviewParameter(ElementCallConnectionFailedPreviewParam::class) failed: ElementCallConnection.Failed) = ElementCallPreview {
    CallFailure(failed = failed, onClose = {})
}
