/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrixrtc.api

/**
 * Matrix event types used by MatrixRTC.
 */
object MatrixRtcEventTypes {
    /**
     * MSC4143 membership, carried as an MSC4354 sticky event.
     *
     * The stable name is only accepted when *reading*: ruma serialises this event under
     * [MEMBER_UNSTABLE], and that is what other clients put on the wire. The RTC core deals
     * exclusively in this name, so the bridge translates at the edges.
     */
    const val MEMBER = "m.rtc.member"

    /** The type membership events are actually published under. */
    const val MEMBER_UNSTABLE = "org.matrix.msc4143.rtc.member"

    /** Both spellings, for matching incoming events. */
    val MEMBER_TYPES = setOf(MEMBER, MEMBER_UNSTABLE)

    /**
     * Per-participant media encryption keys, distributed over to-device.
     *
     * This is the type the RTC core sends under, so it is the one we have to listen for.
     */
    const val ENCRYPTION_KEY = "org.matrix.msc4143.rtc.encryption_key"

    /**
     * The same thing as Element Call sends it: a `keys` array rather than a single `media_key`.
     *
     * A to-device message has exactly one type, so a peer speaking this dialect sends its keys under
     * this name *instead of* [ENCRYPTION_KEY] - which is why we subscribe to both regardless of the
     * compatibility mode. The content shapes are not interchangeable and the library parses this one
     * itself, so it is handed over raw rather than through
     * [io.element.android.libraries.matrixrtc.impl.EncryptionKeyMapper].
     */
    const val ENCRYPTION_KEY_ELEMENT_CALL = "io.element.call.encryption_keys"

    /**
     * Element Call's pre-MSC4354 membership, carried as a room state event rather than a sticky one.
     *
     * Only meaningful in [MatrixRtcElementCallCompat.STATE_EVENTS]. Both spellings exist in the wild;
     * the unstable one is what that generation actually publishes.
     */
    const val MEMBER_ELEMENT_CALL_STATE = "m.call.member"

    /** See [MEMBER_ELEMENT_CALL_STATE]. */
    const val MEMBER_ELEMENT_CALL_STATE_UNSTABLE = "org.matrix.msc3401.call.member"

    /**
     * Both spellings, for matching incoming legacy state memberships.
     *
     * Only one of them is ever *subscribed* to: ruma treats [MEMBER_ELEMENT_CALL_STATE] as an alias
     * of [MEMBER_ELEMENT_CALL_STATE_UNSTABLE], so a single subscription on the unstable spelling
     * already returns both. This set is what the events that come back are matched against.
     */
    val MEMBER_ELEMENT_CALL_STATE_TYPES = setOf(MEMBER_ELEMENT_CALL_STATE, MEMBER_ELEMENT_CALL_STATE_UNSTABLE)
}
