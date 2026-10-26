/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.element.android.call.test.scenario.MatrixRtcScenario
import io.element.android.call.ui.ElementCallOverlay
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * The host, reduced to what a host does: wrap its content in [ElementCallOverlay] and decide the style.
 * Everything below the overlay is the sample's own screen, which the call minimizes over.
 *
 * With a scenario playing, a scrubber floats over everything, here and over the call screen: the one
 * piece of chrome that is the harness's rather than the call's or the host's.
 */
@Composable
fun SampleApp(
    harness: SampleHarness,
    scenarios: ImmutableList<String>,
    modifier: Modifier = Modifier,
    /** What the launch intent asked for and the sample does not have, shown over the picker. */
    launchError: String? = null,
) {
    val controller = harness.controller
    val fixtures = remember { SampleFixture.entries.toImmutableList() }
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        SampleLockScreenCall.schedule(context, harness)
    }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(modifier = modifier) {
            ElementCallOverlay(
                controller = controller,
                style = if (harness.isStyleOverridden) rememberLoudElementCallStyle() else null,
            ) { contentModifier ->
                SampleHomeScreen(
                    fixtures = fixtures,
                    scenarios = scenarios,
                    launchError = launchError,
                    isStyleOverridden = harness.isStyleOverridden,
                    onToggleStyle = { harness.isStyleOverridden = !harness.isStyleOverridden },
                    onAnswerOnLockScreen = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            SampleLockScreenCall.schedule(context, harness)
                        }
                    },
                    onOpen = {
                        harness.stopScenario()
                        controller.start(it.snapshot())
                    },
                    onOpenRinging = {
                        harness.stopScenario()
                        controller.startRinging(it)
                    },
                    onOpenOutage = {
                        harness.stopScenario()
                        controller.startOutage(it)
                    },
                    onOpenScenario = { name -> harness.play(MatrixRtcScenario.load(name)) },
                    modifier = contentModifier,
                )
            }
            harness.player?.let { ScenarioBar(player = it) }
        }
    }
}

/**
 * One line over the top of the stage, as the iOS sample has it: play or pause, step to the next
 * frame, then the step, the time, and the frame last applied as the file writes it. Small and see-
 * through, so the tiles under it stay readable.
 */
@Composable
internal fun BoxScope.ScenarioBar(player: SampleScenarioPlayer) {
    Row(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(top = 60.dp, start = 12.dp, end = 12.dp)
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .clip(RoundedCornerShape(percent = 50))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = { player.isPlaying = !player.isPlaying }, modifier = Modifier.size(BAR_HEIGHT)) {
            Icon(
                imageVector = if (player.isPlaying) PauseIcon else PlayIcon,
                contentDescription = if (player.isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = player::stepForward, enabled = player.step < player.stepCount, modifier = Modifier.size(BAR_HEIGHT)) {
            Icon(
                imageVector = StepIcon,
                contentDescription = "Next frame",
                tint = if (player.step < player.stepCount) Color.White else Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp),
            )
        }
        Text(text = "${player.step}/${player.stepCount}", style = MaterialTheme.typography.labelLarge, color = Color.White)
        Text(text = "${player.currentTimeMs / MS_PER_S}s", style = MaterialTheme.typography.labelLarge, color = Color.White)
        Text(
            text = player.current ?: player.scenario.name,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
    }
}

private val BAR_HEIGHT = 36.dp
private const val MS_PER_S = 1_000L

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

/** A bar and a play triangle: forward by one frame. */
private val StepIcon: ImageVector by lazy {
    ImageVector.Builder(name = "step", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        path(fill = androidx.compose.ui.graphics.SolidColor(Color.White)) {
            moveTo(5f, 5f)
            lineTo(9f, 5f)
            lineTo(9f, 19f)
            lineTo(5f, 19f)
            close()
            moveTo(11f, 5f)
            lineTo(21f, 12f)
            lineTo(11f, 19f)
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
