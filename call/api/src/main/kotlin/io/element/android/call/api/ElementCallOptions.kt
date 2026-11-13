/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api

import io.element.android.call.api.rtc.MatrixRtcLoggingConfiguration
import kotlin.time.Duration

/**
 * The knobs a host may turn. Everything has a default; the sample app passes none.
 *
 * The membership format is not one of them: it is a device preference, [ElementCallPreferencesStore.membershipFormat].
 */
data class ElementCallOptions(
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
    /**
     * Whether our media may move to another member's media server once ours has been down for a while (feature-hq 034 R25,
     * C11). Off by default: it only works where that server accepts users from other homeservers, and which deployments do is
     * something only the host can know, since the SDK keeps nothing of a LiveKit transport but its URL. Read when media connects.
     */
    val isSfuFallbackEnabled: Boolean = false,
    /**
     * Whether the host is in developer mode: the settings menu then offers Developer options, with tile stats, the membership
     * format and the video codec (feature-hq 043 R4). Off, the choices already made still apply.
     */
    val isDeveloperModeEnabled: Boolean = false,
) {
    companion object {
        const val DEFAULT_PARTICIPANT_CAP_FOR_JOIN_LEAVE_SOUNDS = 8
    }
}
