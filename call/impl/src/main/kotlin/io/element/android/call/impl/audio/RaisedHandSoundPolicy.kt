/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.audio

/**
 * Whether a hand going up makes a sound (009 R34).
 *
 * Which hands are announced at all is the core's: only one seen going up while we are in the call. What is
 * left here is silence one to one, where no hand is shown (R33), and one sound for hands raised close
 * together rather than a clipped one.
 */
internal class RaisedHandSoundPolicy {
    private var lastPlayedAtMs: Long? = null
    private var isSilenced = false

    /** Records the start of a sound it allows. */
    fun shouldPlay(isOneToOne: Boolean, atMs: Long): Boolean {
        if (isSilenced || isOneToOne) return false
        val last = lastPlayedAtMs
        if (last != null && atMs - last < SOUND_LENGTH_MS) return false
        lastPlayedAtMs = atMs
        return true
    }

    /** For good: the call has ended for us. */
    fun silence() {
        isSilenced = true
    }

    companion object {
        /** `element_call_raise_hand.mp3`'s length; the asset is pinned by its hash, so this cannot drift from it. */
        const val SOUND_LENGTH_MS = 648L
    }
}
