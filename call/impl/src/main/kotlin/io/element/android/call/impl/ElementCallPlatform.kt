/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl

import android.content.Context
import android.os.SystemClock
import io.element.android.call.impl.services.ElementCallForegroundService

/**
 * The Android surface a running call needs, behind an interface so [DefaultElementCallController] can be
 * tested on the JVM.
 *
 * Both members are static platform calls that throw outside an instrumented environment - starting a
 * service needs a real `Context`, and `SystemClock` is one of the stubbed-out `android.jar` methods.
 * Neither is worth a Robolectric runner on tests that are otherwise about call logic.
 */
internal interface ElementCallPlatform {
    /**
     * Milliseconds since boot, monotonic.
     *
     * Not wall clock: a duration counted from this cannot jump when the system clock is corrected
     * mid-call, which is exactly when a call is most likely to be long enough for it to matter.
     */
    fun elapsedRealtimeMs(): Long

    /**
     * Start, or restart, the call foreground service.
     *
     * Restarting is how the service picks up a permission granted since it started - the camera, in
     * practice - so this is called again rather than only once.
     *
     * @param isProjecting whether to claim the `mediaProjection` service type as well. From Android
     * 14 the service must already be running with that type when the projection is claimed, so this
     * has to be called - and have returned - before the screen capture starts. Passed explicitly
     * rather than derived from a permission because there is no permission to derive it from: the
     * grant is a one-shot token from a system dialog, not something [android.content.Context] can be
     * asked about. It is also dropped again when sharing stops, so the system's screen-recording
     * indicator does not outlive the share.
     */
    fun startForegroundService(isProjecting: Boolean = false)

    fun stopForegroundService()
}

internal class DefaultElementCallPlatform(
    private val context: Context,
) : ElementCallPlatform {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

    override fun startForegroundService(isProjecting: Boolean) = ElementCallForegroundService.start(context, isProjecting)

    override fun stopForegroundService() = ElementCallForegroundService.stop(context)
}
