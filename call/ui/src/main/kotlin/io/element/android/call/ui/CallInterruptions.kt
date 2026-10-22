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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.element.android.call.api.ElementCallInterruption
import io.element.android.call.ui.preview.ElementCallPreview
import io.element.android.call.ui.preview.PreviewsDayNight
import io.element.android.call.ui.theme.ElementCallTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * The service interruptions indicator, and the list it opens (034 R35, R36): one indicator however many causes, never colour
 * alone. Drawn only with the stage's chrome for now, as on iOS, where it covered tiles; that waits on design.
 */
@Composable
internal fun CallInterruptions(
    interruptions: ImmutableList<ElementCallInterruption>,
    isIndicatorVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    var isListVisible by rememberSaveable { mutableStateOf(false) }
    AnnounceInterruptions(isInterrupted = interruptions.isNotEmpty())
    // The list follows the causes in place, and goes on its own only with the last of them (R36).
    LaunchedEffect(interruptions.isEmpty()) {
        if (interruptions.isEmpty()) isListVisible = false
    }
    if (isIndicatorVisible && interruptions.isNotEmpty()) {
        InterruptionsIndicator(onClick = { isListVisible = true }, modifier = modifier)
    }
    if (isListVisible) {
        InterruptionsSheet(interruptions = interruptions, onDismiss = { isListVisible = false })
    }
}

@Composable
private fun InterruptionsIndicator(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(ElementCallTheme.colors.overlayScrim)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = ElementCallTheme.icons.interruptions,
            contentDescription = null,
            tint = ElementCallTheme.colors.onOverlay,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(R.string.element_call_interruptions),
            style = ElementCallTheme.typography.bodySmMedium,
            color = ElementCallTheme.colors.onOverlay,
            maxLines = 1,
        )
        Icon(
            imageVector = ElementCallTheme.icons.chevronRight,
            contentDescription = null,
            tint = ElementCallTheme.colors.onOverlay,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** A sheet even though it covers some of the call: the list is short, and goes with the last cause (R36). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InterruptionsSheet(interruptions: ImmutableList<ElementCallInterruption>, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // The host's Material theme would paint it otherwise, as AudioDevicePicker explains.
        containerColor = ElementCallTheme.colors.bgSubtlePrimary,
        contentColor = ElementCallTheme.colors.textPrimary,
    ) {
        InterruptionsList(interruptions, modifier = Modifier.navigationBarsPadding())
    }
}

/** One entry per kind of trouble, in the order the core's causes are grouped (R13, R23, R29). Read when focused, never announced (R40). */
@Composable
internal fun InterruptionsList(interruptions: ImmutableList<ElementCallInterruption>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.element_call_interruptions),
            style = ElementCallTheme.typography.bodyLgMedium,
            color = ElementCallTheme.colors.textPrimary,
        )
        interruptions.forEach { interruption ->
            Text(
                text = interruption.text(),
                style = ElementCallTheme.typography.bodyMdRegular,
                color = ElementCallTheme.colors.textPrimary,
            )
        }
    }
}

/** The lapse changes what the server entry says rather than adding an entry of its own (R13). */
@Composable
private fun ElementCallInterruption.text(): String = stringResource(
    when (this) {
        is ElementCallInterruption.OwnServer ->
            if (isMembershipLapsed) R.string.element_call_interruption_own_server_lapsed else R.string.element_call_interruption_own_server
        ElementCallInterruption.OwnMedia -> R.string.element_call_interruption_own_media
        ElementCallInterruption.OthersMedia -> R.string.element_call_interruption_others_media
    }
)

/** Said when the first cause comes and when the last goes, whether or not the indicator is drawn (R40). */
@Composable
private fun AnnounceInterruptions(isInterrupted: Boolean) {
    val view = LocalView.current
    val interrupted = stringResource(R.string.element_call_interruptions)
    val resolved = stringResource(R.string.element_call_interruptions_resolved)
    var last by remember { mutableStateOf(isInterrupted) }
    LaunchedEffect(isInterrupted) {
        if (last == isInterrupted) return@LaunchedEffect
        last = isInterrupted
        @Suppress("DEPRECATION")
        view.announceForAccessibility(if (isInterrupted) interrupted else resolved)
    }
}

internal class InterruptionsImmutableListPreviewParam : PreviewParameterProvider<ImmutableList<ElementCallInterruption>> {
    override val values = sequenceOf(
        persistentListOf(ElementCallInterruption.OthersMedia),
        persistentListOf(ElementCallInterruption.OwnServer(isMembershipLapsed = false), ElementCallInterruption.OthersMedia),
        persistentListOf(ElementCallInterruption.OwnServer(isMembershipLapsed = true), ElementCallInterruption.OwnMedia, ElementCallInterruption.OthersMedia),
    )
}

@PreviewsDayNight
@Composable
internal fun InterruptionsListPreview(
    @PreviewParameter(InterruptionsImmutableListPreviewParam::class) interruptions: ImmutableList<ElementCallInterruption>,
) = ElementCallPreview {
    InterruptionsList(interruptions, modifier = Modifier.background(ElementCallTheme.colors.bgSubtlePrimary))
}

@PreviewsDayNight
@Composable
internal fun InterruptionsIndicatorPreview() = ElementCallPreview {
    InterruptionsIndicator(onClick = {})
}
