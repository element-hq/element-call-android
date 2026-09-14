/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrixrtc.api

/**
 * Which generation of Element Call, if any, a session should be able to call.
 *
 * Element Call on the JS SDK is the only other MatrixRTC implementation there is to test against, and
 * there are two pre-2026 generations of it. They disagree about the *carrier* of a membership rather
 * than merely its fields - one publishes MSC4354 sticky events, the other room state - so they are
 * mutually exclusive by construction rather than a set of flags.
 *
 * Chosen once per join and remembered for the room, because it is one decision rather than a
 * wire-format switch: it also fixes the member id the session joins with, how an inbound media key is
 * bound to a membership, the participant identity the SFU assigns, and which authorisation endpoint
 * mints the token. Those four disagreeing is not an error but a silence - a fully connected call in
 * which nothing decrypts and nobody appears.
 *
 * Reading the *older* format is opt-in in both directions, unlike [STICKY_EVENTS] whose reader is
 * always on: [STATE_EVENTS] looks at a different event type in a different part of the room, so left
 * enabled everywhere any room that once hosted an Element Call would show a call that ended months
 * ago.
 */
enum class MatrixRtcElementCallCompat {
    /**
     * Speak only MSC4143 as it currently stands. The right choice for calling another Element X.
     *
     * Legacy *reading* is not disabled by this: the library passes a spec-shaped event through
     * untouched and only applies a legacy rule where the modern field is absent, so a sticky-event
     * Element Call peer is still understood. What this turns off is publishing anything they can
     * read.
     */
    OFF,

    /**
     * Element Call as it is deployed today: MSC4143 membership carried as an MSC4354 sticky event,
     * with `member: {user_id, device_id, id}` and no `membership`, a flat `rtc_transports` array, a
     * leave whose content is a bare sticky key, and media keys as `io.element.call.encryption_keys`.
     *
     * Our join stays MSC4143-valid with the legacy fields riding alongside, so this remains callable
     * by an [OFF] peer. A leave and a media key cannot be both at once, so those go out in the legacy
     * shape only.
     */
    STICKY_EVENTS,

    /**
     * The generation before MSC4354 existed: membership as `org.matrix.msc3401.call.member` **room
     * state**, keyed `_{user}_{device}_{application}{call_id}`, with the dead man's switch as a
     * delayed *state* event.
     *
     * Not an additive rewrite - the carrier, the SFU identity derivation, the token endpoint and the
     * content shape all change together - so a call joined this way is visible to that generation of
     * Element Call and to nobody else, an Element X peer included.
     *
     * Two-way: the state carrying their membership is read back as well as written, so their peers
     * appear in the roster and can be given a media key.
     */
    STATE_EVENTS,
}
