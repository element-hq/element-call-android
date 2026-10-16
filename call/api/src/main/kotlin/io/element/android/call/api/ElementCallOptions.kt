/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api

import io.element.android.call.api.rtc.MatrixRtcLoggingConfiguration
import io.element.android.call.api.rtc.MatrixRtcMembershipFormat
import kotlin.time.Duration

/**
 * The knobs a host may turn. Everything has a default; the sample app passes none.
 */
data class ElementCallOptions(
    /**
     * Which generation of Element Call a call should be reachable by. See [MatrixRtcMembershipFormat].
     *
     * Temporary: pinned to [MatrixRtcMembershipFormat.ROOM_STATE], whatever is asked for, until the two
     * sticky modes (MSC4354) have been tried against other clients (`docs/FEEDBACK.md`, "SDK backend"). A
     * different value is logged so the pin is visible; lifting it is a one-line change in the controller.
     */
    val membershipFormat: MatrixRtcMembershipFormat = MatrixRtcMembershipFormat.ROOM_STATE,
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
    /**
     * Above this many participants after a change, ourselves included, a join or a leave plays no sound.
     * Not a user setting.
     */
    val participantCapForJoinLeaveSounds: Int = DEFAULT_PARTICIPANT_CAP_FOR_JOIN_LEAVE_SOUNDS,
    /**
     * How long a DM call rings the other person, and waits for them, before giving up. Null is the core's
     * 90 s, Element Call web's; the core caps it at 120 s.
     */
    val ringLifetime: Duration? = null,
) {
    companion object {
        const val DEFAULT_PARTICIPANT_CAP_FOR_JOIN_LEAVE_SOUNDS = 8
    }
}
