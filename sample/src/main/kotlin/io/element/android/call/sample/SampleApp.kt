/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import io.element.android.call.test.scenario.MatrixRtcScenario
import io.element.android.call.ui.ElementCallOverlay
import kotlinx.collections.immutable.toImmutableList

/**
 * The host, reduced to what a host does: wrap its content in [ElementCallOverlay] and decide the style.
 * Everything below the overlay is the sample's own screen, which the call minimizes over.
 *
 * With a scenario playing, a scrubber floats over everything: the one piece of chrome that is the
 * harness's rather than the call's or the host's.
 */
@Composable
fun SampleApp(
    controller: SampleElementCallController,
    modifier: Modifier = Modifier,
    initialScenario: MatrixRtcScenario? = null,
) {
    var isStyleOverridden by rememberSaveable { mutableStateOf(false) }
    val fixtures = remember { SampleFixture.entries.toImmutableList() }
    var player by remember { mutableStateOf(initialScenario?.let { SampleScenarioPlayer(controller, it) }) }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(modifier = modifier) {
            ElementCallOverlay(
                controller = controller,
                // Null is the library's defaults; the override is the proof that the port reaches every colour.
                style = if (isStyleOverridden) rememberLoudElementCallStyle() else null,
            ) { contentModifier ->
                SampleHomeScreen(
                    fixtures = fixtures,
                    scenarios = SampleActivity.SCENARIOS.toImmutableList(),
                    isStyleOverridden = isStyleOverridden,
                    onToggleStyle = { isStyleOverridden = !isStyleOverridden },
                    onOpen = {
                        player = null
                        controller.start(it.snapshot())
                    },
                    onOpenScenario = { name -> player = SampleScenarioPlayer(controller, MatrixRtcScenario.load(name)) },
                    modifier = contentModifier,
                )
            }
            player?.let { current ->
                ScenarioScrubber(
                    player = current,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 56.dp, start = 24.dp, end = 24.dp),
                )
            }
        }
    }
}

/** Play, pause and scrub a scenario; the cue is the action the person has to perform themselves. */
@Composable
private fun ScenarioScrubber(player: SampleScenarioPlayer, modifier: Modifier = Modifier) {
    LaunchedEffect(player) { player.start() }
    LaunchedEffect(player, player.isPlaying) { if (player.isPlaying) player.play() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { player.isPlaying = !player.isPlaying }) {
                Icon(
                    imageVector = if (player.isPlaying) PauseIcon else PlayIcon,
                    contentDescription = if (player.isPlaying) "Pause" else "Play",
                    tint = Color.White,
                )
            }
            Slider(
                value = player.positionMs.toFloat(),
                onValueChange = {
                    player.isPlaying = false
                    player.seekTo(it.toLong())
                },
                valueRange = 0f..player.durationMs.toFloat().coerceAtLeast(1f),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${player.positionMs / MS_PER_S}.${player.positionMs % MS_PER_S / MS_PER_TENTH}s / ${player.durationMs / MS_PER_S}s",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            text = player.cue?.let { "→ $it" } ?: player.scenario.name,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
        )
    }
}

private const val MS_PER_S = 1_000L
private const val MS_PER_TENTH = 100L

private val PlayIcon: ImageVector by lazy {
    ImageVector.Builder(name = "play", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        path(fill = androidx.compose.ui.graphics.SolidColor(Color.White)) {
            moveTo(8f, 5f)
            lineTo(19f, 12f)
            lineTo(8f, 19f)
            close()
        }
    }.build()
}

private val PauseIcon: ImageVector by lazy {
    ImageVector.Builder(name = "pause", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        path(fill = androidx.compose.ui.graphics.SolidColor(Color.White)) {
            moveTo(6f, 5f)
            lineTo(10f, 5f)
            lineTo(10f, 19f)
            lineTo(6f, 19f)
            close()
            moveTo(14f, 5f)
            lineTo(18f, 5f)
            lineTo(18f, 19f)
            lineTo(14f, 19f)
            close()
        }
    }.build()
}
