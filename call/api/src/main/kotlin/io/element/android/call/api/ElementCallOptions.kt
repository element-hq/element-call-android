/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api

import io.element.android.call.api.rtc.MatrixRtcElementCallCompat
import io.element.android.call.api.rtc.MatrixRtcLoggingConfiguration

/**
 * The knobs a host may turn. Everything has a default; the sample app passes none.
 */
data class ElementCallOptions(
    /**
     * Which generation of Element Call a call should be reachable by. See [MatrixRtcElementCallCompat].
     *
     * Temporary: pinned to [MatrixRtcElementCallCompat.STATE_EVENTS] while the library builds against the
     * released Rust SDK, whatever is asked for. The two sticky modes need MSC4354, which the widget-driver
     * stopgap cannot carry (`docs/FEEDBACK.md`, "Widget-driver stopgap"). A different value is logged so the
     * pin is visible; lifting it is a one-line change in the controller.
     */
    val elementCallCompat: MatrixRtcElementCallCompat = MatrixRtcElementCallCompat.STATE_EVENTS,
    /**
     * How the RTC core logs, or null to leave the core silent. Applied when the native library is first
     * loaded; only the first configuration in a process takes effect.
     */
    val logging: MatrixRtcLoggingConfiguration? = null,
    /** The ongoing-call notification the foreground service posts. */
    val notification: ElementCallNotificationConfig = ElementCallNotificationConfig(),
    /**
     * Whether the user may share their screen. Off, the control bar has no share button and the
     * controller refuses to start a share.
     *
     * Off by default because turning it on is not free for the host: publishing a projection needs the
     * `mediaProjection` foreground service type and `FOREGROUND_SERVICE_MEDIA_PROJECTION`, both
     * Play-reviewed, which the host declares on `ElementCallForegroundService` in its own manifest
     * (README, host requirements). Enabling this without them is a `SecurityException` on Android 14+.
     */
    val isScreenSharingEnabled: Boolean = false,
)
