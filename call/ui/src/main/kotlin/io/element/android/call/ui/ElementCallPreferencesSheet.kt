/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.element.android.call.ui.preview.ElementCallPreview
import io.element.android.call.ui.preview.PreviewsDayNight
import io.element.android.call.ui.theme.ElementCallTheme

/**
 * The call's Preferences, what every user can change (feature-hq 043 R9): Reactions, Sound effects and About. Developer
 * options has a sheet of its own.
 */
@Composable
internal fun ElementCallPreferencesSheet(state: ElementCallScreenState) {
    val dismiss = { state.eventSink(ElementCallScreenEvent.DismissSettingsSheet) }
    ElementCallSettingsModalSheet(onDismiss = dismiss, testTag = ElementCallTestTags.PREFERENCES_SHEET) {
        ElementCallPreferencesContent(state = state)
    }
}

@Composable
internal fun ElementCallPreferencesContent(state: ElementCallScreenState, modifier: Modifier = Modifier) {
    ElementCallSettingsPage(
        title = stringResource(R.string.element_call_preferences_title),
        modifier = modifier,
    ) {
        ElementCallSettingsSection(stringResource(R.string.element_call_preferences_reactions_title)) {
            ElementCallSettingsToggle(
                title = stringResource(R.string.element_call_preferences_reactions_show),
                description = stringResource(R.string.element_call_preferences_reactions_show_description),
                checked = state.showsReactions,
                onCheckedChange = { state.eventSink(ElementCallScreenEvent.SetShowsReactions(it)) },
                testTag = ElementCallTestTags.PREFERENCES_SHOW_REACTIONS,
            )
            HorizontalDivider(color = ElementCallTheme.colors.borderControl, modifier = Modifier.padding(horizontal = 16.dp))
            // Greyed while reactions are hidden, holding the user's choice for when they are shown again (043 R7).
            ElementCallSettingsToggle(
                title = stringResource(R.string.element_call_preferences_reactions_sounds),
                description = stringResource(R.string.element_call_preferences_reactions_sounds_description),
                checked = state.playsReactionSounds,
                enabled = state.showsReactions,
                onCheckedChange = { state.eventSink(ElementCallScreenEvent.SetPlaysReactionSounds(it)) },
                testTag = ElementCallTestTags.PREFERENCES_PLAY_REACTION_SOUNDS,
            )
        }
        ElementCallSettingsSection(stringResource(R.string.element_call_preferences_sound_effects)) {
            SoundEffectsVolumeRow(
                volume = state.soundEffectsVolume,
                onChange = { state.eventSink(ElementCallScreenEvent.SetSoundEffectsVolume(it)) },
                onRelease = { state.eventSink(ElementCallScreenEvent.PlaySoundEffectsVolumeSample) },
            )
        }
        ElementCallSettingsSection(stringResource(R.string.element_call_preferences_about)) {
            Column(modifier = Modifier.testTag(ElementCallTestTags.PREFERENCES_VERSION)) {
                ElementCallSettingsPlainRow(stringResource(R.string.element_call_preferences_version, state.libraryVersion))
                HorizontalDivider(color = ElementCallTheme.colors.borderControl, modifier = Modifier.padding(horizontal = 16.dp))
                ElementCallSettingsPlainRow(stringResource(R.string.element_call_preferences_core_version, state.coreVersion))
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

// The sheet itself needs a real window, so its content is previewed instead, as the audio device sheet's rows are.
@PreviewsDayNight
@Composable
internal fun ElementCallPreferencesContentPreview(@PreviewParameter(PreferencesElementCallScreenStatePreviewParam::class) state: ElementCallScreenState) =
    ElementCallPreview {
        ElementCallPreferencesContent(state = state, modifier = Modifier.background(ElementCallTheme.colors.bgSubtlePrimary))
    }

open class PreferencesElementCallScreenStatePreviewParam : PreviewParameterProvider<ElementCallScreenState> {
    override val values: Sequence<ElementCallScreenState>
        get() = sequenceOf(
            anElementCallScreenState(),
            anElementCallScreenState(playsReactionSounds = false, soundEffectsVolume = 0.2f),
            // Greyed, in both positions: the user's choice is kept (043 R7).
            anElementCallScreenState(showsReactions = false, playsReactionSounds = true),
            anElementCallScreenState(showsReactions = false, playsReactionSounds = false),
        )
}
