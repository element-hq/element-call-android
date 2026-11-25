/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.core.content.getSystemService
import io.element.android.call.api.audio.AudioFocus
import timber.log.Timber

/**
 * Voice-communication focus over `AudioManager`, the call's share of Element X's `DefaultAudioFocus`.
 */
class DefaultAudioFocus(
    context: Context,
) : AudioFocus {
    private val audioManager = requireNotNull(context.getSystemService<AudioManager>())

    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusChangeListener: AudioManager.OnAudioFocusChangeListener? = null

    @Suppress("DEPRECATION")
    override fun requestAudioFocus(onFocusLost: () -> Unit) {
        val listener = AudioManager.OnAudioFocusChangeListener {
            when (it) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    // Do nothing
                    Timber.d("AudioFocus: AUDIOFOCUS_GAIN")
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    // Permanent focus loss (e.g., phone call) - always report.
                    Timber.d("AudioFocus: AUDIOFOCUS_LOSS")
                    onFocusLost()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    Timber.d("AudioFocus: transient loss ($it)")
                    onFocusLost()
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener(listener)
                // No action when focus is lost, so being ducked is as good as pausing.
                .setWillPauseWhenDucked(true)
                .build()
            audioManager.requestAudioFocus(request)
            audioFocusRequest = request
        } else {
            audioManager.requestAudioFocus(
                listener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            )
            audioFocusChangeListener = listener
        }
    }

    @Suppress("DEPRECATION")
    override fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            audioFocusChangeListener?.let { audioManager.abandonAudioFocus(it) }
        }
    }
}
