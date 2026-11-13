/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample.lab

import android.content.Context
import androidx.core.content.edit
import io.element.android.call.api.ElementCallPreferences
import io.element.android.call.api.ElementCallPreferencesStore
import io.element.android.call.api.rtc.MatrixRtcMembershipFormat
import io.element.android.call.api.rtc.MatrixRtcVideoCodec

/**
 * The lab's preferences, as a host with its own store keeps them (feature-hq 043 R13). The format is the kept lab session's:
 * a scanned code sets it, and so does Developer options, so the Lab screen shows what the next join uses.
 */
class SampleLabPreferencesStore(context: Context) : ElementCallPreferencesStore {
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences("lab_preferences", Context.MODE_PRIVATE)

    override var showsReactions: Boolean
        get() = prefs.getBoolean("shows_reactions", ElementCallPreferences.DEFAULT.showsReactions)
        set(value) = prefs.edit { putBoolean("shows_reactions", value) }

    override var playsReactionSounds: Boolean
        get() = prefs.getBoolean("plays_reaction_sounds", ElementCallPreferences.DEFAULT.playsReactionSounds)
        set(value) = prefs.edit { putBoolean("plays_reaction_sounds", value) }

    override var soundEffectsVolume: Float
        get() = prefs.getFloat("sound_effects_volume", ElementCallPreferences.DEFAULT.soundEffectsVolume)
        set(value) = prefs.edit { putFloat("sound_effects_volume", value) }

    override var membershipFormat: MatrixRtcMembershipFormat
        get() = SampleLabSession.Saved.load(context)?.format ?: ElementCallPreferences.DEFAULT.membershipFormat
        set(value) = SampleLabSession.Saved.storeFormat(context, value)

    override var videoCodec: MatrixRtcVideoCodec
        get() = prefs.getString("video_codec", null)?.let { name -> MatrixRtcVideoCodec.entries.firstOrNull { it.name == name } }
            ?: ElementCallPreferences.DEFAULT.videoCodec
        set(value) = prefs.edit { putString("video_codec", value.name) }
}
