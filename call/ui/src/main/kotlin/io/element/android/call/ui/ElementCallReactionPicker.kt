/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.element.android.call.api.rtc.MatrixRtcReactionKind
import io.element.android.call.ui.preview.ElementCallPreview
import io.element.android.call.ui.preview.PreviewsDayNight
import io.element.android.call.ui.theme.ElementCallTheme
import kotlinx.collections.immutable.ImmutableList

/**
 * The core's reactions, in its order (feature-hq 009 R11). Modal: the sheet's scrim and window take every touch, so
 * neither a pick nor a dismissal reaches a tile underneath (R18). A pick sends and closes (R19); closing sends nothing (R20).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ElementCallReactionPicker(state: ElementCallScreenState) {
    val dismiss = { state.eventSink(ElementCallScreenEvent.DismissSheet) }
    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden),
        containerColor = ElementCallTheme.colors.bgSubtlePrimary,
        contentColor = ElementCallTheme.colors.textPrimary,
        modifier = Modifier.testTag(ElementCallTestTags.REACTION_SHEET),
    ) {
        ElementCallReactionPickerContent(
            catalogue = state.reactionCatalogue,
            onSend = { state.eventSink(ElementCallScreenEvent.SendReaction(it)) },
        )
    }
}

@Composable
internal fun ElementCallReactionPickerContent(
    catalogue: ImmutableList<MatrixRtcReactionKind>,
    onSend: (MatrixRtcReactionKind) -> Unit,
) {
    Column(modifier = Modifier.navigationBarsPadding().padding(bottom = PICKER_PADDING)) {
        Text(
            text = stringResource(R.string.element_call_reactions_title),
            style = ElementCallTheme.typography.bodyLgMedium,
            color = ElementCallTheme.colors.textPrimary,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = PICKER_PADDING),
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = PICKER_PADDING), contentAlignment = Alignment.Center) {
            // A narrow phone shrinks the circles and keeps the gaps; the last row stays in the others' columns.
            val size = minOf(REACTION_SIZE, (maxWidth - REACTION_GAP * (COLUMNS - 1)) / COLUMNS)
            Column(
                modifier = Modifier.width(size * COLUMNS + REACTION_GAP * (COLUMNS - 1)),
                verticalArrangement = Arrangement.spacedBy(REACTION_GAP),
            ) {
                catalogue.chunked(COLUMNS).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(REACTION_GAP)) {
                        row.forEach { kind -> ReactionButton(kind = kind, size = size, onClick = { onSend(kind) }) }
                    }
                }
            }
        }
    }
}

/** An action of its own for a screen reader, named by its emoji (009 R21). */
@Composable
private fun ReactionButton(kind: MatrixRtcReactionKind, size: Dp, onClick: () -> Unit) {
    val label = stringResource(R.string.element_call_a11y_send_reaction_emoji, kind.emoji)
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .border(1.dp, ElementCallTheme.colors.borderInteractiveSecondary, CircleShape)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .testTag(ElementCallTestTags.reaction(kind.name)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = kind.emoji,
            fontSize = with(LocalDensity.current) { (size * EMOJI_SCALE).toSp() },
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/** Design's: six to a row, 48dp ringed circles 8dp apart, in 16dp of sheet. */
private const val COLUMNS = 6
private val REACTION_SIZE = 48.dp
private val REACTION_GAP = 8.dp
private val PICKER_PADDING = 16.dp
private const val EMOJI_SCALE = 0.5f

// The sheet itself needs a real window, so what it holds is previewed.
@PreviewsDayNight
@Composable
internal fun ElementCallReactionPickerContentPreview() = ElementCallPreview {
    Box(modifier = Modifier.background(ElementCallTheme.colors.bgSubtlePrimary)) {
        ElementCallReactionPickerContent(catalogue = A_PREVIEW_REACTION_CATALOGUE, onSend = {})
    }
}
