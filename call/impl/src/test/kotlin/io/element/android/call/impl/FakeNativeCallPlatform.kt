/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl


class FakeNativeCallPlatform(
    private var elapsedRealtimeMs: Long = 0L,
    /**
     * Called as the service is started, before the caller carries on.
     *
     * The only way to observe *ordering* rather than mere occurrence: a test can look at what has and
     * has not happened yet at this exact moment, which two separate after-the-fact counters cannot
     * distinguish.
     */
    private val onStartForegroundService: (isProjecting: Boolean) -> Unit = {},
) : NativeCallPlatform {
    /** Counted rather than flagged: the service is deliberately started twice, once per media permission. */
    var startForegroundServiceCount = 0
    var stopForegroundServiceCount = 0

    /**
     * Every `isProjecting` the service was started with, in order.
     *
     * The order is the point: Android 14 requires the `mediaProjection` type to be claimed before the
     * projection itself, so a test has to be able to see that this happened first rather than merely
     * that it happened.
     */
    val startForegroundServiceProjecting = mutableListOf<Boolean>()

    override fun elapsedRealtimeMs(): Long = elapsedRealtimeMs

    override fun startForegroundService(isProjecting: Boolean) {
        startForegroundServiceCount++
        startForegroundServiceProjecting += isProjecting
        onStartForegroundService(isProjecting)
    }

    override fun stopForegroundService() {
        stopForegroundServiceCount++
    }
}
