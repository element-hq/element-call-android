/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api

import io.element.android.call.api.rtc.MatrixRtcConnectionState
import io.element.android.call.api.rtc.MatrixRtcInterruptions
import io.element.android.call.api.rtc.MatrixRtcSignallingHealth

/**
 * One entry of the service interruptions list, grouped by whose side the trouble is on (feature-hq 034 R13, R23, R29).
 * Three at most, in this order, however many causes the core reports; the tiles and the controls carry the detail.
 */
sealed interface ElementCallInterruption {
    /**
     * Our homeserver does not answer; [isMembershipLapsed] once the others have dropped us (R17). A participant whose
     * membership has not reached us (the record's `unmappable`) is not an entry: there is nothing the user can do about it.
     */
    data class OwnServer(val isMembershipLapsed: Boolean) : ElementCallInterruption

    /** Our own media server is being reconnected (R23). */
    data object OwnMedia : ElementCallInterruption

    /** Our connection to somebody else's media is down (R29). Not a missing key: only that member's tile says so (Q8). */
    data object OthersMedia : ElementCallInterruption

    companion object {
        /**
         * The core's causes as entries (R38). [signalling] comes apart from [record] because it is the only cause that exists
         * before media does (C4): a join made during an outage has a server entry and no record yet.
         */
        fun entries(signalling: MatrixRtcSignallingHealth, record: MatrixRtcInterruptions?): List<ElementCallInterruption> = buildList {
            val causes = record ?: MatrixRtcInterruptions.NONE
            // A lapse with signalling back is the moment before the republish lands, and still true.
            if (signalling is MatrixRtcSignallingHealth.Unreachable || causes.isOwnMembershipLapsed) {
                add(OwnServer(isMembershipLapsed = causes.isOwnMembershipLapsed))
            }
            // Connecting is the start of the call, which the screen already says (C11).
            if (causes.ownMedia == MatrixRtcConnectionState.RECONNECTING || causes.ownMedia == MatrixRtcConnectionState.UNREACHABLE) {
                add(OwnMedia)
            }
            if (causes.membersReconnecting.isNotEmpty() || causes.membersUnreachable.isNotEmpty()) {
                add(OthersMedia)
            }
        }
    }
}
