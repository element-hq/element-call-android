/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc

/**
 * How a call publishes and reads memberships: which generation of Element Call, if any, it can call.
 *
 * Chosen once per room, because it is one decision rather than a
 * wire-format switch: it also fixes the member id the session joins with, how an inbound media key is
 * bound to a membership, and the participant identity the SFU assigns. Those disagreeing is not an error
 * but a silence - a fully connected call in which nothing decrypts and nobody appears.
 *
 * Reading the older format is opt-in in both directions: [ROOM_STATE] looks at a different event type in
 * a different part of the room, so left enabled everywhere any room that once hosted an Element Call
 * would show a call that ended months ago.
 */
enum class MatrixRtcMembershipFormat {
    /**
     * MSC4143 as it currently stands, carried as MSC4354 sticky events. The right choice for calling
     * another Element X. A 2025 Element Call membership is still read.
     */
    MSC,

    /**
     * The generation before MSC4354 existed: membership as `org.matrix.msc3401.call.member` **room
     * state**, keyed `_{user}_{device}_{application}{call_id}`, with the dead man's switch as a
     * delayed *state* event.
     *
     * Not an additive rewrite - the carrier, the SFU identity derivation and the content shape all
     * change together - so a call joined this way is visible to that generation of Element Call and to
     * nobody else, an Element X peer included.
     *
     * Two-way: the state carrying their membership is read back as well as written, so their peers
     * appear in the roster and can be given a media key.
     */
    ROOM_STATE,
}
