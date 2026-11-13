/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl

import io.element.android.call.api.ElementCallPreferences
import io.element.android.call.api.ElementCallPreferencesStore
import io.element.android.call.api.rtc.MatrixRtcMembershipFormat
import io.element.android.call.api.rtc.MatrixRtcVideoCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber

/** The user's preferences as the controller shows them: read from [store] once, and every change written back to it. */
internal class StoredElementCallPreferences(private val store: ElementCallPreferencesStore) {
    private val _value = MutableStateFlow(
        ElementCallPreferences(
            showsReactions = store.showsReactions,
            playsReactionSounds = store.playsReactionSounds,
            soundEffectsVolume = store.soundEffectsVolume.coerceIn(0f, 1f),
            membershipFormat = store.membershipFormat,
            videoCodec = store.videoCodec,
        )
    )

    val value: StateFlow<ElementCallPreferences> = _value.asStateFlow()

    fun setShowsReactions(shows: Boolean) {
        Timber.i("ElementCall: reactions ${if (shows) "shown" else "hidden"}")
        store.showsReactions = shows
        _value.update { it.copy(showsReactions = shows) }
    }

    // Accepted while reactions are hidden: the greyed switch is the screen's business, and a host's own settings may write it.
    fun setPlaysReactionSounds(plays: Boolean) {
        Timber.i("ElementCall: reaction sounds ${if (plays) "on" else "off"}")
        store.playsReactionSounds = plays
        _value.update { it.copy(playsReactionSounds = plays) }
    }

    /** @return the volume as kept, 0 to 1. */
    fun setSoundEffectsVolume(volume: Float): Float {
        val clamped = volume.coerceIn(0f, 1f)
        store.soundEffectsVolume = clamped
        _value.update { it.copy(soundEffectsVolume = clamped) }
        return clamped
    }

    // Not gated on developer mode: a failed MSC join offers Room state to everyone (043 R18a). Read at the next join (R14).
    fun setMembershipFormat(format: MatrixRtcMembershipFormat) {
        Timber.i("ElementCall: membership format $format from the next join")
        store.membershipFormat = format
        _value.update { it.copy(membershipFormat = format) }
    }

    fun setVideoCodec(codec: MatrixRtcVideoCodec) {
        Timber.i("ElementCall: video codec $codec from the next join")
        store.videoCodec = codec
        _value.update { it.copy(videoCodec = codec) }
    }
}
