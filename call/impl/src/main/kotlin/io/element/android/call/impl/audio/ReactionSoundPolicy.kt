/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.audio

/**
 * Whether a reaction sound may start, and how loud the ones playing should be (feature-hq 037 R10).
 *
 * Every reaction plays over the others, itself included, and none is ever cut short: past [MAXIMUM_PLAYING] the new
 * one plays nothing. `SoundPool` would steal the oldest stream instead, which is why the count is kept here.
 */
internal class ReactionSoundPolicy {
    private val endsAtMs = mutableListOf<Long>()

    /** Records the sound if it may start. */
    fun tryStart(atMs: Long, durationMs: Long): Boolean {
        endsAtMs.removeAll { it <= atMs }
        if (endsAtMs.size >= MAXIMUM_PLAYING) return false
        endsAtMs += atMs + durationMs
        return true
    }

    fun playingCount(atMs: Long): Int {
        endsAtMs.removeAll { it <= atMs }
        return endsAtMs.size
    }

    companion object {
        const val MAXIMUM_PLAYING = 8

        /**
         * The share of the volume each of [playing] reaction sounds gets: together they never exceed it, however their
         * peaks line up, so a burst cannot clip. `SoundPool` mixes in the platform, where no limiter can sit.
         */
        fun headroom(playing: Int): Float = 1f / playing.coerceAtLeast(1)
    }
}
