/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.audio

/**
 * Whether an announced join or leave makes a sound.
 *
 * Which changes are announced at all is the core's: nothing for the roster we arrive to, nothing while
 * our own membership is missing, nothing after the call ended. What is left here is the participant cap
 * and the throttle per kind.
 */
internal class JoinLeaveSoundPolicy(private val participantCap: Int) {
    private val lastPlayedAtMs = mutableMapOf<CallSoundEffect, Long>()
    private var isSilenced = false

    /** [participantCount] is the core's count after the change, ourselves included. Records the start of a sound it allows. */
    fun shouldPlay(sound: CallSoundEffect, participantCount: Int, atMs: Long): Boolean {
        if (isSilenced || participantCount > participantCap) return false
        val last = lastPlayedAtMs[sound]
        if (last != null && atMs - last < THROTTLE_MS) return false
        lastPlayedAtMs[sound] = atMs
        return true
    }

    /** For good: the call has ended for us, and teardown must not sound like everyone leaving. */
    fun silence() {
        isSilenced = true
    }

    companion object {
        /** Web's `THROTTLE_SOUND_EFFECT_MS`, counted from the last sound of the same kind starting. */
        const val THROTTLE_MS = 500L
    }
}
