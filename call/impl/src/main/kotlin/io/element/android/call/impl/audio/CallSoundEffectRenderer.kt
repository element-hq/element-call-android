/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.annotation.RawRes
import io.element.android.call.impl.R
import timber.log.Timber

internal enum class CallSoundEffect(@RawRes val resId: Int) {
    JOIN(R.raw.element_call_join_call),
    LEAVE(R.raw.element_call_left_call),
    RAISE_HAND(R.raw.element_call_raise_hand),
}

/** Plays the call's sound effects over the call's own audio. One per call, released when it ends. */
internal interface CallSoundEffectRenderer {
    fun play(sound: CallSoundEffect)

    fun release()
}

/**
 * Over a [SoundPool] with the remote voices' usage, so the sounds take their route, their volume stream and
 * their indifference to the ringer, and are cancelled from our microphone by the platform's echo canceller.
 */
internal class DefaultCallSoundEffectRenderer(context: Context) : CallSoundEffectRenderer {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(CallSoundEffect.entries.size)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val soundIds = CallSoundEffect.entries.associateWith { soundPool.load(context, it.resId, 1) }

    override fun play(sound: CallSoundEffect) {
        val soundId = soundIds.getValue(sound)
        if (soundPool.play(soundId, VOLUME, VOLUME, 1, 0, 1f) == 0) {
            Timber.w("ElementCall: $sound sound not played, not loaded yet")
        }
    }

    override fun release() {
        soundPool.release()
    }

    private companion object {
        /** Half the call's volume, web's default. */
        const val VOLUME = 0.5f
    }
}
