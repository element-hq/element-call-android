/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.element.android.call.ui.theme.ElementCallTheme
import kotlinx.collections.immutable.ImmutableList

/** What a disabled control keeps of its colour: the palette's own, faded, never Material's, which follows the host's theme. */
private const val DISABLED_ALPHA = 0.38f

/**
 * A settings sheet over the lower part of the call screen, which carries on behind it (feature-hq 037 R12, 043 R5).
 *
 * Modal: the scrim takes every touch, and a tap on it only closes the sheet, as do a swipe down and the close button
 * (037 R12a).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ElementCallSettingsModalSheet(onDismiss: () -> Unit, testTag: String, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden),
        // Painted from this component's palette, as the audio device sheet is, never the host's theme.
        containerColor = ElementCallTheme.colors.bgSubtlePrimary,
        contentColor = ElementCallTheme.colors.textPrimary,
        modifier = Modifier.testTag(testTag),
    ) {
        content()
    }
}

/** A sheet's scrolling page: its title with the close button, then its sections. */
@Composable
internal fun ElementCallSettingsPage(
    title: String,
    closeTestTag: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = ElementCallTheme.typography.headingMdBold,
                color = ElementCallTheme.colors.textPrimary,
                modifier = Modifier.align(Alignment.Center),
            )
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterEnd).testTag(closeTestTag)) {
                Icon(
                    imageVector = ElementCallTheme.icons.close,
                    contentDescription = stringResource(R.string.element_call_action_close),
                    tint = ElementCallTheme.colors.iconPrimary,
                )
            }
        }
        content()
    }
}

/** A grey title, when there is one, over a rounded card. */
@Composable
internal fun ElementCallSettingsSection(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) {
            Text(
                text = title,
                style = ElementCallTheme.typography.bodyMdMedium,
                color = ElementCallTheme.colors.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(ElementCallTheme.colors.bgSubtleSecondary, RoundedCornerShape(16.dp)),
            content = content,
        )
    }
}

@Composable
internal fun ElementCallSettingsRowTitle(title: String, description: String?, modifier: Modifier = Modifier, isEnabled: Boolean = true) {
    val alpha = if (isEnabled) 1f else DISABLED_ALPHA
    Column(modifier = modifier) {
        Text(text = title, style = ElementCallTheme.typography.bodyLgMedium, color = ElementCallTheme.colors.textPrimary.copy(alpha = alpha))
        if (description != null) {
            Text(text = description, style = ElementCallTheme.typography.bodyMdRegular, color = ElementCallTheme.colors.textSecondary.copy(alpha = alpha))
        }
    }
}

/**
 * One on/off setting. The row is the one target, a switch for TalkBack; the switch itself takes no input, so a setting is
 * not two targets. Disabled, the row neither changes nor acts, keeps its value, and is still read, as a disabled switch
 * (043 R7, C10).
 */
@Composable
internal fun ElementCallSettingsToggle(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(16.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ElementCallSettingsRowTitle(title = title, description = description, isEnabled = enabled, modifier = Modifier.weight(1f))
        val colors = ElementCallTheme.colors
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.bgSubtlePrimary,
                checkedTrackColor = colors.iconAccent,
                uncheckedThumbColor = colors.textPrimary,
                uncheckedTrackColor = colors.borderControl,
                uncheckedBorderColor = colors.borderControl,
                disabledCheckedThumbColor = colors.bgSubtlePrimary,
                disabledCheckedTrackColor = colors.iconAccent.copy(alpha = DISABLED_ALPHA),
                disabledUncheckedThumbColor = colors.textPrimary.copy(alpha = DISABLED_ALPHA),
                disabledUncheckedTrackColor = colors.borderControl.copy(alpha = DISABLED_ALPHA),
                disabledUncheckedBorderColor = colors.borderControl.copy(alpha = DISABLED_ALPHA),
            ),
        )
    }
}

/**
 * One choice among a few: a titled card of rows with a check on the chosen one, each read as a radio button, and the
 * description under the rows (043 C10). Rows rather than a dropdown, which is a popup a screenshot cannot capture.
 */
@Composable
internal fun <T> ElementCallSettingsChoices(
    title: String,
    options: ImmutableList<T>,
    selected: T,
    label: (T) -> String,
    description: String,
    testTag: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ElementCallSettingsSection(title = title) {
            Column(modifier = Modifier.selectableGroup()) {
                options.forEachIndexed { index, option ->
                    if (index > 0) HorizontalDivider(color = ElementCallTheme.colors.borderControl, modifier = Modifier.padding(horizontal = 16.dp))
                    val isSelected = option == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(option) })
                            .padding(16.dp)
                            .testTag(testTag(option)),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = label(option),
                            style = ElementCallTheme.typography.bodyLgMedium,
                            color = ElementCallTheme.colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = ElementCallTheme.icons.selected,
                                contentDescription = null,
                                tint = ElementCallTheme.colors.iconAccent,
                                modifier = Modifier.size(24.dp),
                            )
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
        Text(
            text = description,
            style = ElementCallTheme.typography.bodyMdRegular,
            color = ElementCallTheme.colors.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/** A row of plain text, as the About section's. */
@Composable
internal fun ElementCallSettingsPlainRow(text: String) {
    Text(
        text = text,
        style = ElementCallTheme.typography.bodyLgMedium,
        color = ElementCallTheme.colors.textPrimary,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    )
}
