/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api

/**
 * Why a call ended, for a host that tells its system call surface who ended it (feature-hq 030 R32):
 * [DECLINED], [UNANSWERED] and [OTHERS_LEFT] are the other side; the rest are not.
 */
enum class ElementCallEndReason {
    /** We hung up, or the host ended the call. */
    LEFT,

    /** The callee declined our ring. */
    DECLINED,

    /** Nobody answered before the ring ran out. */
    UNANSWERED,

    /** The ring could not be sent in time. */
    RING_NOT_SENT,

    /** Everyone else left a DM call. */
    OTHERS_LEFT,

    /** A DM call we answered or joined had nobody left in it. */
    EMPTY,

    /** The call's slot was closed, which ended the call without us asking. */
    SLOT_CLOSED,

    /** The connection to the media server closed and did not come back. */
    CONNECTION_CLOSED,
}
