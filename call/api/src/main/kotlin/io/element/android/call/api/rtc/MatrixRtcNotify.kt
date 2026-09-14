/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrixrtc.api

import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.matrix.api.notification.RtcNotificationType

/**
 * Ask for an MSC4075 notification to be sent with a join, so that the other devices in the room ring
 * or show an incoming call.
 *
 * Only pass one when the user is *starting* a call. Joining one someone else started happens quietly,
 * and the core suppresses the notification anyway if anybody is already in the session - but wanting
 * to summon people at all is the app's statement to make, not something the core can infer.
 *
 * The receiving half of this already exists: a notification lands on the far end as a push for
 * `org.matrix.msc4075.rtc.notification`, resolved into [RtcNotificationType] and [CallIntent], which
 * is why those types are reused here rather than restated - the same two values ride both directions.
 */
data class MatrixRtcNotify(
    /**
     * [RtcNotificationType.RING] rings audibly for [lifetimeMs]; [RtcNotificationType.NOTIFY] only
     * shows an incoming call. Ringing a large room summons everyone in it, so the choice belongs to
     * whoever knows what kind of room this is.
     */
    val type: RtcNotificationType,
    /** MSC4196 `m.call.intent`, which tells the callee what they are being invited to. */
    val intent: CallIntent?,
    /**
     * How long the ring stays valid. Null defers to the core's 30 s, which is also what Element Call
     * web puts on the wire; a receiver honours the shorter of this and its own limit, so raising it
     * does not by itself make a phone ring for longer.
     */
    val lifetimeMs: ULong? = null,
    /** Whether the whole room is being summoned, which is what a call in a room means. */
    val mentionRoom: Boolean = true,
    /** Users to name individually in `m.mentions`. Usually empty. */
    val mentionUserIds: List<UserId> = emptyList(),
)
