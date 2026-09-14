/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.callnative.impl.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/**
 * How long the call has been connected, as `mm:ss`, ticking once a second for as long as it is
 * composed.
 *
 * Driven from the elapsed-realtime stamp the controller took at connect rather than by counting its
 * own ticks, so a label that was off screen for a minute comes back showing the right time rather
 * than one minute behind. Shared by the minimized bar and the one-to-one call screen, so the two
 * can never disagree about the same call.
 */
@Composable
internal fun rememberCallDuration(connectedAtElapsedMs: Long?): String {
    if (connectedAtElapsedMs == null) return ""
    // Previews and screenshot tests have no clock worth reading, and a ticking value would make
    // every screenshot differ from the last.
    if (LocalInspectionMode.current) return "00:00"
    var elapsedSeconds by remember(connectedAtElapsedMs) { mutableLongStateOf(0L) }
    LaunchedEffect(connectedAtElapsedMs) {
        while (true) {
            elapsedSeconds = (SystemClock.elapsedRealtime() - connectedAtElapsedMs) / 1000
            delay(1.seconds)
        }
    }
    val minutes = elapsedSeconds / 60
    val seconds = elapsedSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
