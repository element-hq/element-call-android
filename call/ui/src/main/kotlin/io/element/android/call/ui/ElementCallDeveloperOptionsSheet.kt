/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.call.api.rtc.MatrixRtcMembershipFormat
import io.element.android.call.api.rtc.MatrixRtcVideoCodec
import io.element.android.call.ui.preview.ElementCallPreview
import io.element.android.call.ui.preview.PreviewsDayNight
import io.element.android.call.ui.theme.ElementCallTheme
import kotlinx.collections.immutable.toImmutableList

/**
 * The developer's sheet, reached from the settings menu in developer mode only (feature-hq 043 R4): Tile stats, the
 * membership format and the video codec, in that order (C10). The format and the codec apply from the next join (R14, R16a).
 */
@Composable
internal fun ElementCallDeveloperOptionsSheet(state: ElementCallScreenState) {
    val dismiss = { state.eventSink(ElementCallScreenEvent.DismissSettingsSheet) }
    ElementCallSettingsModalSheet(onDismiss = dismiss, testTag = ElementCallTestTags.DEVELOPER_OPTIONS_SHEET) {
        ElementCallDeveloperOptionsContent(state = state)
    }
}

@Composable
internal fun ElementCallDeveloperOptionsContent(state: ElementCallScreenState, modifier: Modifier = Modifier) {
    ElementCallSettingsPage(
        title = stringResource(R.string.element_call_preferences_developer_options),
        modifier = modifier,
    ) {
        // Untitled: under a sheet called Developer options, a section of the same name reads twice.
        ElementCallSettingsSection(title = null) {
            ElementCallSettingsToggle(
                title = stringResource(R.string.element_call_preferences_tile_stats),
                description = stringResource(R.string.element_call_preferences_tile_stats_description),
                checked = state.isTileStatsVisible,
                onCheckedChange = { state.eventSink(ElementCallScreenEvent.ToggleTileStats) },
                testTag = ElementCallTestTags.DEVELOPER_OPTIONS_TILE_STATS,
            )
        }
        // Either format can always be chosen (R18).
        val formats = MatrixRtcMembershipFormat.entries.toImmutableList()
        val formatLabels = formats.associateWith { it.label() }
        ElementCallSettingsChoices(
            title = stringResource(R.string.element_call_developer_options_membership_format),
            options = formats,
            selected = state.membershipFormat,
            label = formatLabels::getValue,
            description = stringResource(R.string.element_call_developer_options_next_join),
            testTag = ElementCallTestTags::developerOptionsMembershipFormat,
            onSelect = { state.eventSink(ElementCallScreenEvent.SetMembershipFormat(it)) },
        )
        val codecLabels = state.videoCodecs.associateWith { it.label() }
        ElementCallSettingsChoices(
            title = stringResource(R.string.element_call_developer_options_video_codec),
            options = state.videoCodecs,
            // A stored codec this device cannot encode joins as Auto, so Auto is what is shown chosen; the stored value stays (C2).
            selected = state.videoCodec.takeIf { it in state.videoCodecs } ?: MatrixRtcVideoCodec.AUTO,
            label = codecLabels::getValue,
            description = stringResource(R.string.element_call_developer_options_video_codec_description),
            testTag = ElementCallTestTags::developerOptionsVideoCodec,
            onSelect = { state.eventSink(ElementCallScreenEvent.SetVideoCodec(it)) },
        )
    }
}

@Composable
private fun MatrixRtcMembershipFormat.label(): String = stringResource(
    when (this) {
        MatrixRtcMembershipFormat.MSC -> R.string.element_call_developer_options_membership_format_msc
        MatrixRtcMembershipFormat.ROOM_STATE -> R.string.element_call_developer_options_membership_format_room_state
    }
)

/** "Auto (VP8)", naming what Auto publishes with (043 R15a), and each codec by its name. */
@Composable
private fun MatrixRtcVideoCodec.label(): String = when (this) {
    MatrixRtcVideoCodec.AUTO -> stringResource(R.string.element_call_developer_options_video_codec_auto, MatrixRtcVideoCodec.autoCodec.label())
    MatrixRtcVideoCodec.VP8 -> stringResource(R.string.element_call_video_codec_vp8)
    MatrixRtcVideoCodec.H264 -> stringResource(R.string.element_call_video_codec_h264)
    MatrixRtcVideoCodec.VP9 -> stringResource(R.string.element_call_video_codec_vp9)
    MatrixRtcVideoCodec.AV1 -> stringResource(R.string.element_call_video_codec_av1)
}

// The sheet itself needs a real window, so its content is previewed instead.
@PreviewsDayNight
@Composable
internal fun ElementCallDeveloperOptionsContentPreview(
    @PreviewParameter(DeveloperOptionsElementCallScreenStatePreviewParam::class) state: ElementCallScreenState,
) = ElementCallPreview {
    ElementCallDeveloperOptionsContent(state = state, modifier = Modifier.background(ElementCallTheme.colors.bgSubtlePrimary))
}

open class DeveloperOptionsElementCallScreenStatePreviewParam : PreviewParameterProvider<ElementCallScreenState> {
    override val values: Sequence<ElementCallScreenState>
        get() = sequenceOf(
            anElementCallScreenState(isDeveloperModeEnabled = true),
            anElementCallScreenState(
                isDeveloperModeEnabled = true,
                isTileStatsVisible = true,
                membershipFormat = MatrixRtcMembershipFormat.MSC,
                videoCodec = MatrixRtcVideoCodec.H264,
            ),
        )
}
