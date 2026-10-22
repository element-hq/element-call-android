/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc

import io.element.android.call.api.rtc.id.UserId

/**
 * Where a joined call is, as the core decides it (feature-hq 030 C3): our media, a DM's ring and pickup,
 * and the end, in one value. The app draws it and never works one out itself.
 */
sealed interface MatrixRtcCallStatus {
    /** Our media is not up yet, and there is somebody to talk to. */
    data object Connecting : MatrixRtcCallStatus

    /** A DM call waiting for [callee] to pick up. [media] is how far our own side has got meanwhile. */
    data class Calling(
        val callee: UserId,
        val ring: MatrixRtcRingPhase,
        val media: MatrixRtcMediaPhase,
    ) : MatrixRtcCallStatus

    /**
     * Media up and somebody to talk to. [sinceMs], in ms since the Unix epoch, is the zero of the call's
     * duration; it holds through [degraded] and a media reconnect.
     */
    data class Connected(val sinceMs: Long, val degraded: Boolean) : MatrixRtcCallStatus

    /** Final. Any end but [MatrixRtcCallEnd.Left] is one the core has already acted on: do not leave again. */
    data class Ended(val reason: MatrixRtcCallEnd) : MatrixRtcCallStatus
}

/** How far a DM call's ring has got. */
sealed interface MatrixRtcRingPhase {
    /** Being sent: we are calling, without a ringback yet. */
    data object Sending : MatrixRtcRingPhase

    /** Sent at [sentAtMs] (ms since the Unix epoch), ringing for [lifetimeMs]. */
    data class Ringing(val sentAtMs: Long, val lifetimeMs: Long) : MatrixRtcRingPhase
}

/** How far our own media has got. */
sealed interface MatrixRtcMediaPhase {
    data object Connecting : MatrixRtcMediaPhase

    /** [degraded] while a transport reconnects. */
    data class Connected(val degraded: Boolean) : MatrixRtcMediaPhase
}

/** Why a call ended. */
sealed interface MatrixRtcCallEnd {
    /** We left, or the room was closed. */
    data object Left : MatrixRtcCallEnd

    /** The callee declined our ring. */
    data object Declined : MatrixRtcCallEnd

    /** Nobody answered before the ring ran out. */
    data object Unanswered : MatrixRtcCallEnd

    /** The ring could not be sent in time. */
    data object RingNotSent : MatrixRtcCallEnd

    /** Everyone else left a DM call. */
    data object OthersLeft : MatrixRtcCallEnd

    /** A DM call joined without a ring had nobody in it. */
    data object Empty : MatrixRtcCallEnd

    /** The call's slot was closed, which ended the call without us asking. */
    data object SlotClosed : MatrixRtcCallEnd

    /** Our own SFU connection was given up: [reason] is for the screen, [message] for logs only. Our leave has been issued. */
    data class ConnectionClosed(val reason: MatrixRtcConnectionLossReason, val message: String) : MatrixRtcCallEnd
}

/** Why our own SFU connection was given up (feature-hq 034 C10). */
enum class MatrixRtcConnectionLossReason {
    /** Lost, and not back within the reconnect budget. */
    BUDGET,

    /** The SFU or its token service refused us. */
    REFUSED,

    /** Never established within the budget from the connect request. */
    NEVER_CONNECTED,
}
