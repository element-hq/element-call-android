/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc

/** The connection carrying a member's media; on our own tile, our own SFU's (feature-hq 034 C6). */
enum class MatrixRtcConnectionState {
    /** Up; a peer's connection also while it is opened for the first time. */
    CONNECTED,

    /** Our own media only, before it is up for the first time: the call is starting, not interrupted. */
    CONNECTING,

    /** Being re-established after a loss, for as long as that takes. */
    RECONNECTING,

    /** No transport can serve what the member advertises. */
    UNREACHABLE,
}

/** Why a member's media is not shown although it may be arriving (feature-hq 034 C6). Draw the avatar for every case. */
enum class MatrixRtcUnableToDecrypt {
    /** Their key has not reached us, after the core's key grace of failing frames. */
    MISSING_KEY,

    /** We hold a key for their frames but it does not decrypt them, after the same grace. */
    BAD_KEY,

    /** They published a track declared unencrypted in an encrypted call, which is refused and never played. */
    NOT_ENCRYPTED,
}
