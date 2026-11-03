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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.element.android.call.api.ElementCallReactionsPreference
import io.element.android.call.ui.preview.ElementCallPreview
import io.element.android.call.ui.preview.PreviewsDayNight
import io.element.android.call.ui.theme.ElementCallTheme

/**
 * The call's Preferences, over the lower part of the call screen, which carries on behind it (feature-hq 037 R12).
 *
 * Modal: the scrim takes every touch, and a tap on it only closes the sheet, as do a swipe down and the close button
 * (R12a). One page of titled sections (R16).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ElementCallPreferencesSheet(state: ElementCallScreenState) {
    val dismiss = { state.eventSink(ElementCallScreenEvent.DismissPreferences) }
    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden),
        // Painted from this component's palette, as the audio device sheet is, never the host's theme.
        containerColor = ElementCallTheme.colors.bgSubtlePrimary,
        contentColor = ElementCallTheme.colors.textPrimary,
        modifier = Modifier.testTag(ElementCallTestTags.PREFERENCES_SHEET),
    ) {
        ElementCallPreferencesContent(state = state, onClose = dismiss)
    }
}

@Composable
internal fun ElementCallPreferencesContent(state: ElementCallScreenState, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.element_call_preferences_title),
                style = ElementCallTheme.typography.headingMdBold,
                color = ElementCallTheme.colors.textPrimary,
                modifier = Modifier.align(Alignment.Center),
            )
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterEnd).testTag(ElementCallTestTags.PREFERENCES_CLOSE)) {
                Icon(
                    imageVector = ElementCallTheme.icons.close,
                    contentDescription = stringResource(R.string.element_call_action_close),
                    tint = ElementCallTheme.colors.iconPrimary,
                )
            }
        }
        PreferencesSection(stringResource(R.string.element_call_preferences_reactions_title)) {
            ReactionsRow(
                preference = state.reactionsPreference,
                onSelect = { state.eventSink(ElementCallScreenEvent.SetReactionsPreference(it)) },
            )
        }
        PreferencesSection(stringResource(R.string.element_call_preferences_sound_effects)) {
            SoundEffectsVolumeRow(
                volume = state.soundEffectsVolume,
                onChange = { state.eventSink(ElementCallScreenEvent.SetSoundEffectsVolume(it)) },
                onRelease = { state.eventSink(ElementCallScreenEvent.PlaySoundEffectsVolumeSample) },
            )
        }
        if (state.isDeveloperModeEnabled) {
            PreferencesSection(stringResource(R.string.element_call_preferences_developer_options)) {
                TileStatsRow(isOn = state.isTileStatsVisible, onToggle = { state.eventSink(ElementCallScreenEvent.ToggleTileStats) })
            }
        }
        PreferencesSection(stringResource(R.string.element_call_preferences_about)) {
            Column(modifier = Modifier.testTag(ElementCallTestTags.PREFERENCES_VERSION)) {
                PlainRow(stringResource(R.string.element_call_preferences_version, state.libraryVersion))
                HorizontalDivider(color = ElementCallTheme.colors.borderControl, modifier = Modifier.padding(horizontal = 16.dp))
                PlainRow(stringResource(R.string.element_call_preferences_core_version, state.coreVersion))
            }
        }
    }
}

/** A grey title over a rounded card. */
@Composable
private fun PreferencesSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = ElementCallTheme.typography.bodyMdMedium,
            color = ElementCallTheme.colors.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(ElementCallTheme.colors.bgSubtleSecondary, RoundedCornerShape(16.dp)),
            content = content,
        )
    }
}

@Composable
private fun RowTitle(title: String, description: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = title, style = ElementCallTheme.typography.bodyLgMedium, color = ElementCallTheme.colors.textPrimary)
        if (description != null) {
            Text(text = description, style = ElementCallTheme.typography.bodyMdRegular, color = ElementCallTheme.colors.textSecondary)
        }
    }
}

/** One setting with three values (R13): its current value on the trailing side, the others in a menu. */
@Composable
private fun ReactionsRow(preference: ElementCallReactionsPreference, onSelect: (ElementCallReactionsPreference) -> Unit) {
    var isExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { isExpanded = true }
            .padding(16.dp)
            .testTag(ElementCallTestTags.PREFERENCES_REACTIONS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RowTitle(
            title = stringResource(R.string.element_call_preferences_reactions_show),
            description = stringResource(R.string.element_call_preferences_reactions_description),
            modifier = Modifier.weight(1f),
        )
        Box {
            Text(text = preference.label(), style = ElementCallTheme.typography.bodyLgMedium, color = ElementCallTheme.colors.textSecondary)
            DropdownMenu(
                expanded = isExpanded,
                onDismissRequest = { isExpanded = false },
                containerColor = ElementCallTheme.colors.bgSubtlePrimary,
            ) {
                ElementCallReactionsPreference.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(text = option.label(), color = ElementCallTheme.colors.textPrimary) },
                        trailingIcon = if (option == preference) {
                            { Icon(ElementCallTheme.icons.selected, contentDescription = null, tint = ElementCallTheme.colors.iconAccent) }
                        } else {
                            null
                        },
                        onClick = {
                            isExpanded = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SoundEffectsVolumeRow(volume: Float, onChange: (Float) -> Unit, onRelease: () -> Unit) {
    val description = stringResource(R.string.element_call_preferences_sound_effects_volume)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = ElementCallTheme.icons.speaker,
            contentDescription = null,
            tint = ElementCallTheme.colors.iconSecondary,
            modifier = Modifier.size(20.dp),
        )
        Slider(
            value = volume,
            onValueChange = onChange,
            onValueChangeFinished = onRelease,
            colors = SliderDefaults.colors(
                thumbColor = ElementCallTheme.colors.textPrimary,
                activeTrackColor = ElementCallTheme.colors.textPrimary,
                inactiveTrackColor = ElementCallTheme.colors.borderControl,
            ),
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = description }
                .testTag(ElementCallTestTags.PREFERENCES_SOUND_EFFECTS_VOLUME),
        )
    }
}

@Composable
private fun TileStatsRow(isOn: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(16.dp)
            .testTag(ElementCallTestTags.PREFERENCES_TILE_STATS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RowTitle(
            title = stringResource(R.string.element_call_preferences_tile_stats),
            description = stringResource(R.string.element_call_preferences_tile_stats_description),
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = isOn,
            // The row toggles it, so the switch is one target for TalkBack rather than two.
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ElementCallTheme.colors.bgSubtlePrimary,
                checkedTrackColor = ElementCallTheme.colors.iconAccent,
                uncheckedThumbColor = ElementCallTheme.colors.textPrimary,
                uncheckedTrackColor = ElementCallTheme.colors.borderControl,
                uncheckedBorderColor = ElementCallTheme.colors.borderControl,
            ),
        )
    }
}

@Composable
private fun PlainRow(text: String) {
    Text(
        text = text,
        style = ElementCallTheme.typography.bodyLgMedium,
        color = ElementCallTheme.colors.textPrimary,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    )
}

@Composable
private fun ElementCallReactionsPreference.label(): String = stringResource(
    when (this) {
        ElementCallReactionsPreference.WITH_SOUND -> R.string.element_call_preferences_reactions_on
        ElementCallReactionsPreference.WITHOUT_SOUND -> R.string.element_call_preferences_reactions_silent
        ElementCallReactionsPreference.OFF -> R.string.element_call_preferences_reactions_off
    }
)

// The sheet itself needs a real window, so its content is previewed instead, as the audio device sheet's rows are.
@PreviewsDayNight
@Composable
internal fun ElementCallPreferencesContentPreview(@PreviewParameter(PreferencesElementCallScreenStatePreviewParam::class) state: ElementCallScreenState) =
    ElementCallPreview {
        ElementCallPreferencesContent(state = state, onClose = {}, modifier = Modifier.background(ElementCallTheme.colors.bgSubtlePrimary))
    }

open class PreferencesElementCallScreenStatePreviewParam : PreviewParameterProvider<ElementCallScreenState> {
    override val values: Sequence<ElementCallScreenState>
        get() = sequenceOf(
            anElementCallScreenState(),
            anElementCallScreenState(reactionsPreference = ElementCallReactionsPreference.WITHOUT_SOUND, soundEffectsVolume = 0.2f),
            anElementCallScreenState(reactionsPreference = ElementCallReactionsPreference.OFF, isDeveloperModeEnabled = true, isTileStatsVisible = true),
        )
}
