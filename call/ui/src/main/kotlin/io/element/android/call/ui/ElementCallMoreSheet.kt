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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.element.android.call.ui.preview.ElementCallPreview
import io.element.android.call.ui.preview.PreviewsDayNight
import io.element.android.call.ui.theme.ElementCallTheme

/**
 * The bar's More: what else the call offers, in R17's order, each only where offered (feature-hq 009 R17). Raise hand
 * and Share screen act and close it; Emoji reactions hands over to the picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ElementCallMoreSheet(state: ElementCallScreenState) {
    val dismiss = { state.eventSink(ElementCallScreenEvent.DismissSheet) }
    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden),
        // Painted from this component's palette, as the other sheets are, never the host's theme.
        containerColor = ElementCallTheme.colors.bgSubtlePrimary,
        contentColor = ElementCallTheme.colors.textPrimary,
        modifier = Modifier.testTag(ElementCallTestTags.MORE_SHEET),
    ) {
        ElementCallMoreSheetContent(state = state)
    }
}

@Composable
internal fun ElementCallMoreSheetContent(state: ElementCallScreenState) {
    Column(modifier = Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
        Text(
            text = stringResource(R.string.element_call_more_title),
            style = ElementCallTheme.typography.headingMdBold,
            color = ElementCallTheme.colors.textPrimary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        state.extraActions.forEach { action ->
            when (action) {
                ElementCallBarAction.RAISED_HAND -> MoreSheetRow(
                    icon = ElementCallTheme.icons.handRaised,
                    text = stringResource(if (state.isHandRaised) R.string.element_call_lower_hand else R.string.element_call_raise_hand),
                    isEnabled = state.isRaiseHandEnabled,
                    testTag = ElementCallTestTags.RAISED_HAND,
                    onClick = { state.eventSink(ElementCallScreenEvent.ToggleRaisedHand) },
                )
                ElementCallBarAction.REACTIONS -> MoreSheetRow(
                    icon = ElementCallTheme.icons.reaction,
                    text = stringResource(R.string.element_call_emoji_reactions),
                    isEnabled = state.isReactionControlEnabled,
                    testTag = ElementCallTestTags.REACTIONS,
                    onClick = { state.eventSink(ElementCallScreenEvent.Present(ElementCallSheet.REACTIONS)) },
                )
                ElementCallBarAction.SCREEN_SHARE -> MoreSheetRow(
                    icon = if (state.isScreenSharing) ElementCallTheme.icons.shareScreenActive else ElementCallTheme.icons.shareScreen,
                    text = stringResource(if (state.isScreenSharing) R.string.element_call_screen_share_stop else R.string.element_call_screen_share_start),
                    isEnabled = state.isScreenSharing || state.isScreenShareStartEnabled,
                    testTag = ElementCallTestTags.SCREEN_SHARE,
                    onClick = { state.eventSink(ElementCallScreenEvent.ToggleScreenShare) },
                )
            }
        }
    }
}

/** Disabled in an outage rather than removed, and said so, as the bar's own controls are (034 R15). */
@Composable
private fun MoreSheetRow(icon: ImageVector, text: String, isEnabled: Boolean, testTag: String, onClick: () -> Unit) {
    val unavailable = stringResource(R.string.element_call_a11y_unavailable)
    val alpha = if (isEnabled) 1f else DISABLED_ROW_ALPHA
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isEnabled, onClick = onClick)
            .semantics { if (!isEnabled) stateDescription = unavailable }
            .testTag(testTag)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = ElementCallTheme.colors.iconPrimary.copy(alpha = alpha))
        Text(text = text, style = ElementCallTheme.typography.bodyMdRegular, color = ElementCallTheme.colors.textPrimary.copy(alpha = alpha))
    }
}

private const val DISABLED_ROW_ALPHA = 0.4f

internal class MoreSheetElementCallScreenStatePreviewParam : PreviewParameterProvider<ElementCallScreenState> {
    override val values = sequenceOf(
        anElementCallScreenState(participants = aSmallGroup(), isScreenShareAvailable = true),
        anElementCallScreenState(participants = aSmallGroup(), handRaisedIds = setOf(A_LOCAL_MEMBER_ID), isScreenShareAvailable = true, isScreenSharing = true),
        anElementCallScreenState(participants = aSmallGroup(), isRaiseHandEnabled = false, isReactionControlEnabled = false),
    )
}

// The sheet itself needs a real window, so what it holds is previewed.
@PreviewsDayNight
@Composable
internal fun ElementCallMoreSheetContentPreview(@PreviewParameter(
    MoreSheetElementCallScreenStatePreviewParam::class
) state: ElementCallScreenState) = ElementCallPreview {
    Box(modifier = Modifier.background(ElementCallTheme.colors.bgSubtlePrimary)) {
        ElementCallMoreSheetContent(state = state)
    }
}
