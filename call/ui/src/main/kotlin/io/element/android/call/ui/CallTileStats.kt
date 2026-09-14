/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.element.android.call.api.rtc.MatrixRtcFrameEncryptionState
import io.element.android.call.api.rtc.MatrixRtcReceiveStats
import io.element.android.call.ui.theme.ElementCallTheme
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/**
 * Counts what a renderer is actually being handed, without touching Compose state.
 *
 * Written from the frame thread thirty times a second, so nothing here may be snapshot state: a
 * `mutableStateOf` written at that rate would recompose the whole tile on every frame to display a
 * number that changes once a second. [CallTileStatsOverlay] samples it on a timer instead, and that
 * sample is the only thing composition ever sees.
 *
 * Plain reads and writes rather than atomics. The writer is one thread, the reader is one thread, and
 * the worst a torn read can do is show a frame rate that is briefly out by one.
 */
class TileFrameCounter {
    @Volatile private var frames = 0
    @Volatile private var lastWidth = 0
    @Volatile private var lastHeight = 0

    fun onFrame(width: Int, height: Int) {
        frames++
        lastWidth = width
        lastHeight = height
    }

    /** Frames since the last call, and the most recent size. Resets the count. */
    fun sample(): FrameSample {
        val count = frames
        frames = 0
        return FrameSample(framesPerInterval = count, width = lastWidth, height = lastHeight)
    }
}

data class FrameSample(val framesPerInterval: Int, val width: Int, val height: Int)

/**
 * What a tile is really receiving, over the tile.
 *
 * A debug tool, and shaped like one: dense, monospaced, and unapologetically ugly. It answers the
 * questions that come up when a call looks wrong and nothing is obviously broken - is this the layer
 * we asked for, is the network dropping it, is it decrypting - none of which are inferable from a
 * picture that has simply gone soft.
 *
 * Deliberately per tile rather than on the diagnostics screen. The diagnostics screen shows the whole
 * call at once and cannot be watched while the call is being used; these numbers are most useful
 * *while* someone is promoting a tile or walking into a lift.
 */
@Composable
internal fun CallTileStatsOverlay(
    counter: TileFrameCounter,
    receiveStats: MatrixRtcReceiveStats?,
    frameEncryption: MatrixRtcFrameEncryptionState?,
    requestedWidth: Int,
    requestedHeight: Int,
    isReachable: Boolean,
    hasMicrophone: Boolean,
    modifier: Modifier = Modifier,
) {
    var sample by remember { mutableStateOf(FrameSample(0, 0, 0)) }
    var bitrateKbps by remember { mutableLongStateOf(0L) }
    var previousBytes by remember { mutableStateOf<Long?>(null) }

    // Once a second, which is both fast enough to be useful and slow enough that reading these
    // numbers is not itself a reason the call is slow.
    LaunchedEffect(counter) {
        while (true) {
            delay(SAMPLE_INTERVAL)
            sample = counter.sample()
        }
    }
    LaunchedEffect(receiveStats?.bytesReceived) {
        val bytes = receiveStats?.bytesReceived ?: return@LaunchedEffect
        val previous = previousBytes
        previousBytes = bytes
        if (previous != null && bytes >= previous) {
            bitrateKbps = (bytes - previous) * 8 / 1000
        }
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(ElementCallTheme.colors.statsBackground)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        // What arrived, against what we asked for. Side by side because that pair is the whole story
        // of whether simulcast is doing its job: a strip tile asking for 507x380 and receiving
        // 1280x720 is the exact failure `setConstraints` exists to prevent.
        StatLine("${sample.width}x${sample.height} @ ${sample.framesPerInterval}fps")
        StatLine("ask ${requestedWidth}x$requestedHeight")
        if (receiveStats != null) {
            StatLine("$bitrateKbps kbps · ${receiveStats.packetsLost} lost")
            StatLine("jit ${(receiveStats.jitter * 1000).toInt()}ms · drop ${receiveStats.framesDropped}")
        }
        if (frameEncryption != null) {
            StatLine("e2ee ${frameEncryption.name.lowercase()}")
        }
        // Only when it is false, which is the whole point of showing it: a member in the call but not
        // reachable from our transport is what a multi-SFU call looks like when a leg is down, and it
        // is the only signal the FFI gives us about which SFU is carrying whom.
        if (!isReachable) {
            StatLine("UNREACHABLE")
        }
        // The name pill draws a mute badge for this too, because "cannot be heard" is what a viewer
        // needs from a badge. Here the question is why, and "they muted" and "we were never given
        // their audio" are different answers - the second one is a fault, and the far end will be
        // hearing them perfectly well while we say they are muted.
        if (!hasMicrophone) {
            StatLine("NO MIC STREAM")
        }
    }
}

@Composable
private fun StatLine(text: String) {
    Text(
        text = text,
        color = ElementCallTheme.colors.onOverlay,
        // Monospaced so the numbers do not dance as they change, which is most of what makes a live
        // readout hard to read. Smaller than any product type scale on purpose: this has to fit over
        // a 100dp thumbnail without covering the person in it.
        style = ElementCallTheme.typography.bodyXsRegular.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            lineHeight = 11.sp,
        ),
    )
}

private val SAMPLE_INTERVAL = 1.seconds
