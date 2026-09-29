/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize

/**
 * What the scenario harness reads off the stage and the commands it drives it with, set only by a
 * test; production never provides the local, so nothing here costs a real call anything.
 *
 * Read, never observed: the harness reads after it has advanced the clock and let the composition
 * settle, so plain fields are enough and they do not invalidate anything.
 */
internal class CallStageTestHooks {
    var isMounted: Boolean = false
    var layout: CallStageLayout? = null
    var stageSize: IntSize = IntSize.Zero
    var scrollOffset: () -> Float = { 0f }

    /** The tiles whose stream is live right now, reported by the tiles themselves. */
    val liveIds: MutableSet<String> = mutableSetOf()

    /** The grid tiles composed: within the band, or beyond it inside the linger. */
    var composedGridIds: Set<String> = emptySet()
    var spotlightTileId: String? = null
    var fullscreenTileId: String? = null
    var heroes: List<String> = emptyList()
    var eventSink: (ElementCallScreenEvent) -> Unit = {}
    var scrollTo: (Float) -> Unit = {}

    /** As a fling would, rather than jumping: what a person watching a scenario on a device expects. */
    var animateScrollTo: (Float) -> Unit = {}

    /** Stands in for the system bar inset the control bar is padded by, which a JVM has none of. */
    var bottomInset: Dp? = null
}

@Suppress("CompositionLocalAllowlist")
internal val LocalCallStageTestHooks = staticCompositionLocalOf<CallStageTestHooks?> { null }

/** A tile tells the harness whether its stream is live, and forgets itself when it goes. */
@Composable
internal fun ReportLiveToHooks(tileId: String, isLive: Boolean) {
    val hooks = LocalCallStageTestHooks.current ?: return
    SideEffect { if (isLive) hooks.liveIds += tileId else hooks.liveIds -= tileId }
    DisposableEffect(tileId) { onDispose { hooks.liveIds -= tileId } }
}
