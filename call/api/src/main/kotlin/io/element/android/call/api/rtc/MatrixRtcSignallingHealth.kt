/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc

/** Whether the homeserver is answering the core's sends, for the whole account (feature-hq 034 C4). Distinct from media health. */
sealed interface MatrixRtcSignallingHealth {
    data object Reachable : MatrixRtcSignallingHealth

    /** Since the first transient failure of a send, or the host's offline report, in ms since the Unix epoch. */
    data class Unreachable(val sinceMs: Long) : MatrixRtcSignallingHealth
}
