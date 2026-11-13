/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api

import io.element.android.call.api.rtc.MatrixRtcMembershipFormat
import io.element.android.call.api.rtc.MatrixRtcVideoCodec

/** The user's in-call preferences, as they stand. */
data class ElementCallPreferences(
    /** Whether received reactions are shown (feature-hq 043 R6). Off, nothing is shown or played. */
    val showsReactions: Boolean,
    /** Whether a shown reaction plays its sound (043 R6). Kept while [showsReactions] is off, so it comes back as left (R7). */
    val playsReactionSounds: Boolean,
    /** How loud every call sound effect plays, 0 to 1 of the call's own level (037 R22). */
    val soundEffectsVolume: Float,
    /** The format the next join uses (043 R13). A call in progress keeps the one it joined with (R14). */
    val membershipFormat: MatrixRtcMembershipFormat,
    /** The codec the next join publishes video with (043 R15, R16a). */
    val videoCodec: MatrixRtcVideoCodec,
) {
    companion object {
        /**
         * What nobody has changed yet: reactions with their sound, web's sound level, room state so that native callers
         * meet Element Call web callers (043 R13a), and the core's own codec.
         */
        val DEFAULT = ElementCallPreferences(
            showsReactions = true,
            playsReactionSounds = true,
            soundEffectsVolume = 0.5f,
            membershipFormat = MatrixRtcMembershipFormat.ROOM_STATE,
            videoCodec = MatrixRtcVideoCodec.AUTO,
        )
    }
}

/**
 * Where the user's in-call preferences are kept: read when the stack is built, written when the user changes one.
 *
 * A port rather than an [ElementCallOptions] member: options are the host's, these are the user's, changed mid-call and
 * expected back next week (037 R14, R15). The default keeps them on the device; a host that wants them in its own
 * settings, or wants to choose the membership format itself (043 R13), implements this.
 */
interface ElementCallPreferencesStore {
    var showsReactions: Boolean

    var playsReactionSounds: Boolean

    /** Clamped to 0..1 by whoever reads it. */
    var soundEffectsVolume: Float

    var membershipFormat: MatrixRtcMembershipFormat

    var videoCodec: MatrixRtcVideoCodec
}
