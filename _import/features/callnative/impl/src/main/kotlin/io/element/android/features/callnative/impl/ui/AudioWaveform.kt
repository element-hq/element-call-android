/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.callnative.impl.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import kotlinx.coroutines.delay

/**
 * A scrolling bar chart of one member's recent audio level.
 *
 * It advances on its own clock rather than on level updates, so the bars keep moving even when
 * nothing arrives: a flat line that scrolls is silence, one that freezes is a stream that stopped.
 * Bars live in a ring buffer and the cursor is the only Compose state, so scrolling costs nothing
 * beyond the redraw.
 */
@Composable
fun AudioWaveform(
    level: Float,
    modifier: Modifier = Modifier,
    color: Color = ElementTheme.colors.iconAccentTertiary,
) {
    val bars = remember { FloatArray(BAR_COUNT) }
    var cursor by remember { mutableIntStateOf(0) }
    val latestLevel by rememberUpdatedState(level)

    LaunchedEffect(Unit) {
        while (true) {
            delay(BAR_DURATION_MS)
            bars[cursor % BAR_COUNT] = latestLevel
            cursor++
        }
    }

    Canvas(modifier = modifier.height(WAVEFORM_HEIGHT)) {
        val slotWidth = size.width / BAR_COUNT
        val barWidth = slotWidth * BAR_WIDTH_FRACTION
        // Reading the cursor here is what ties the redraw to the tick; the bars themselves are a
        // plain array, which Compose does not observe.
        val oldest = cursor
        repeat(BAR_COUNT) { index ->
            val value = bars[(oldest + index) % BAR_COUNT]
            val barHeight = (size.height * value).coerceAtLeast(MIN_BAR_HEIGHT_PX)
            drawRect(
                color = color,
                topLeft = Offset(x = index * slotWidth, y = (size.height - barHeight) / 2f),
                size = Size(width = barWidth, height = barHeight),
            )
        }
    }
}

/** Five seconds of history, at one bar per [BAR_DURATION_MS]. */
private const val BAR_COUNT = 50
private const val BAR_DURATION_MS = 100L
private const val BAR_WIDTH_FRACTION = 0.6f
private const val MIN_BAR_HEIGHT_PX = 2f
private val WAVEFORM_HEIGHT = 24.dp

@PreviewsDayNight
@Composable
internal fun AudioWaveformPreview(@PreviewParameter(AudioLevelFloatPreviewParam::class) level: Float) = ElementPreview {
    AudioWaveform(level = level)
}

internal class AudioLevelFloatPreviewParam : PreviewParameterProvider<Float> {
    override val values = sequenceOf(0f, 0.4f, 0.9f)
}
