/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.SoundPool
import androidx.annotation.RawRes
import io.element.android.call.impl.R
import io.element.android.call.impl.util.runCatchingExceptions
import kotlinx.coroutines.delay
import timber.log.Timber

internal enum class CallSoundEffect(@RawRes val resId: Int) {
    JOIN(R.raw.element_call_join_call),
    LEAVE(R.raw.element_call_left_call),
    RAISE_HAND(R.raw.element_call_raise_hand),
    RINGBACK(R.raw.element_call_ringtone),
    DECLINED(R.raw.element_call_call_declined),
    TIMEOUT(R.raw.element_call_call_timeout),
}

/** Plays the call's sound effects over the call's own audio. One per call, released when it ends. */
internal interface CallSoundEffectRenderer {
    fun play(sound: CallSoundEffect)

    /** Loops [sound] back to back until [stopLoop]; already looping, it carries on. */
    fun startLoop(sound: CallSoundEffect)

    /** Stops the loop at once, mid-sound if need be. */
    fun stopLoop()

    /** Plays [sound] and returns once it has finished, so the call's audio is released after it, not under it. */
    suspend fun playToEnd(sound: CallSoundEffect)

    fun release()
}

/**
 * Over a [SoundPool] with the remote voices' usage, so the sounds take their route, their volume stream and
 * their indifference to the ringer, and are cancelled from our microphone by the platform's echo canceller.
 */
internal class DefaultCallSoundEffectRenderer(private val context: Context) : CallSoundEffectRenderer {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(CallSoundEffect.entries.size)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val loaded = mutableSetOf<Int>()
    private var loop: CallSoundEffect? = null
    private var loopStreamId = 0

    init {
        // A loop asked for before its sound has loaded starts once it has: the ringback is wanted the moment
        // the ring goes out, which can be right after this renderer is created.
        soundPool.setOnLoadCompleteListener { _, soundId, status ->
            if (status != 0) return@setOnLoadCompleteListener
            synchronized(this) {
                loaded += soundId
                val pending = loop
                if (pending != null && loopStreamId == 0 && soundIds[pending] == soundId) playLoop(pending)
            }
        }
    }

    private val soundIds = CallSoundEffect.entries.associateWith { soundPool.load(context, it.resId, 1) }

    override fun play(sound: CallSoundEffect) {
        val soundId = soundIds.getValue(sound)
        if (soundPool.play(soundId, VOLUME, VOLUME, 1, 0, 1f) == 0) {
            Timber.w("ElementCall: $sound sound not played, not loaded yet")
        }
    }

    @Synchronized
    override fun startLoop(sound: CallSoundEffect) {
        if (loop == sound) return
        stopLoop()
        loop = sound
        if (soundIds.getValue(sound) in loaded) playLoop(sound)
    }

    @Synchronized
    override fun stopLoop() {
        if (loopStreamId != 0) soundPool.stop(loopStreamId)
        loopStreamId = 0
        loop = null
    }

    private fun playLoop(sound: CallSoundEffect) {
        loopStreamId = soundPool.play(soundIds.getValue(sound), VOLUME, VOLUME, 1, LOOP_FOREVER, 1f)
    }

    override suspend fun playToEnd(sound: CallSoundEffect) {
        play(sound)
        delay(durationMs(sound))
    }

    private fun durationMs(sound: CallSoundEffect): Long = runCatchingExceptions {
        context.resources.openRawResourceFd(sound.resId).use { fd ->
            // Not `use`: the retriever is only AutoCloseable from API 29.
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                retriever.release()
            }
        }
    }.getOrNull() ?: FALLBACK_DURATION_MS

    override fun release() {
        soundPool.release()
    }

    private companion object {
        /** Half the call's volume, web's default. */
        const val VOLUME = 0.5f
        const val LOOP_FOREVER = -1

        /** Longer than any of web's end sounds, for a file the platform cannot measure. */
        const val FALLBACK_DURATION_MS = 3_000L
    }
}
