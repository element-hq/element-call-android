/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import org.matrix.rtc.reactionCatalog

/**
 * What a received reaction looks and sounds like: Element Call web's answer, so a mixed call shows and plays the same
 * thing on every client (feature-hq 037 R9).
 *
 * The core's catalogue, corrected where it disagrees with web. Each correction is asked of the core (feature-hq
 * `plans/037…/core-rs-feedback.md`, 2026-11-02) and goes when the core gives the same answer.
 */
internal class MatrixRtcReactionCatalogue(kinds: List<Kind>) {
    /** One entry of the core's catalogue: `sound` null where the core calls it silent. */
    data class Kind(val name: String, val emoji: String, val sound: String?)

    data class Resolved(val emoji: String, val sound: String)

    private val catalogue: Map<String, Kind> = buildMap {
        kinds.forEach { putIfAbsent(it.name, it) }
        // Web has a bell; the core's catalogue stops before it.
        putIfAbsent(BELL.name, BELL)
    }

    /** A known name shows the catalogue's emoji whatever was sent; an unknown one shows what was sent, already cut by the core. */
    fun resolve(name: String, emoji: String): Resolved {
        val kind = catalogue[name] ?: return Resolved(emoji, GENERIC_SOUND)
        // The core calls a known name without a sound silent, where web plays its generic sound.
        return Resolved(kind.emoji, kind.sound ?: GENERIC_SOUND)
    }

    companion object {
        /** Web's sound for a name it does not know, and for a known one with none of its own. */
        const val GENERIC_SOUND = "generic"

        private val BELL = Kind(name = "bell", emoji = "🛎️", sound = "bell")

        /** The core's catalogue. Loads the native library on first use. */
        fun fromCore(): MatrixRtcReactionCatalogue {
            MatrixRtcFfi.ensureInitialized()
            return MatrixRtcReactionCatalogue(reactionCatalog().map { Kind(it.name, it.emoji, it.sound) })
        }
    }
}
