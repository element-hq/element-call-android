/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl

import android.content.Context
import androidx.core.content.edit
import io.element.android.call.api.ElementCallPreferences
import io.element.android.call.api.ElementCallPreferencesStore
import io.element.android.call.api.ElementCallReactionsPreference

/** The preferences on this device, surviving a restart, shared with nothing (feature-hq 037 R14). */
internal class DefaultElementCallPreferencesStore(context: Context) : ElementCallPreferencesStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override var reactions: ElementCallReactionsPreference
        get() = prefs.getString(KEY_REACTIONS, null)
            ?.let { name -> ElementCallReactionsPreference.entries.firstOrNull { it.name == name } }
            ?: ElementCallPreferences.DEFAULT.reactions
        set(value) = prefs.edit { putString(KEY_REACTIONS, value.name) }

    override var soundEffectsVolume: Float
        get() = prefs.getFloat(KEY_SOUND_EFFECTS_VOLUME, ElementCallPreferences.DEFAULT.soundEffectsVolume).coerceIn(0f, 1f)
        set(value) = prefs.edit { putFloat(KEY_SOUND_EFFECTS_VOLUME, value.coerceIn(0f, 1f)) }

    companion object {
        /** Named in CHANGES.md, for a host whose backup rules should leave it on the device. */
        const val FILE_NAME = "element_call_preferences"
        private const val KEY_REACTIONS = "reactions"
        private const val KEY_SOUND_EFFECTS_VOLUME = "sound_effects_volume"
    }
}
