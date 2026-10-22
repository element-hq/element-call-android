/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc

/**
 * What is interrupting the call now, cause by cause (feature-hq 034 C5). The core names no entry and no text: grouping is the
 * UI's. Member lists hold member ids on the roster; [unmappable] holds transport identities that map to no member.
 */
data class MatrixRtcInterruptions(
    val signalling: MatrixRtcSignallingHealth,
    val isOwnMembershipLapsed: Boolean,
    /** Authoritative for our own SFU from the first value, before our own tile exists. */
    val ownMedia: MatrixRtcConnectionState,
    val membersReconnecting: List<String>,
    val membersUnreachable: List<String>,
    val unableToDecrypt: List<String>,
    val unmappable: List<String>,
) {
    companion object {
        val NONE = MatrixRtcInterruptions(
            signalling = MatrixRtcSignallingHealth.Reachable,
            isOwnMembershipLapsed = false,
            ownMedia = MatrixRtcConnectionState.CONNECTED,
            membersReconnecting = emptyList(),
            membersUnreachable = emptyList(),
            unableToDecrypt = emptyList(),
            unmappable = emptyList(),
        )
    }
}
