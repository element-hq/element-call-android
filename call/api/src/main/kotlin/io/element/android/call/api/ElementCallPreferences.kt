/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api

/** What the call does with the reactions it receives, as the user chose it (feature-hq 037 R13). */
enum class ElementCallReactionsPreference {
    /** Bubbles and sounds, Element Call web's default. "On" in the sheet. */
    WITH_SOUND,

    /** Bubbles, no sound. "Silent" in the sheet. */
    WITHOUT_SOUND,

    /** Nothing shown, nothing played. */
    OFF,
}

/** The user's in-call preferences, as they stand. */
data class ElementCallPreferences(
    val reactions: ElementCallReactionsPreference,
    /** How loud every call sound effect plays, 0 to 1 of the call's own level (037 R22). */
    val soundEffectsVolume: Float,
) {
    companion object {
        /** Web's defaults, so nothing changes for anyone who never opens the sheet. */
        val DEFAULT = ElementCallPreferences(reactions = ElementCallReactionsPreference.WITH_SOUND, soundEffectsVolume = 0.5f)
    }
}

/**
 * Where the user's in-call preferences are kept: read when the stack is built, written when the user changes one.
 *
 * A port rather than an [ElementCallOptions] member: options are the host's, these are the user's, changed mid-call and
 * expected back next week (037 R14, R15). The default keeps them on the device; a host that wants them in its own
 * settings implements this.
 */
interface ElementCallPreferencesStore {
    var reactions: ElementCallReactionsPreference

    /** Clamped to 0..1 by whoever reads it. */
    var soundEffectsVolume: Float
}
