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
import android.os.SystemClock
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

/** Web's sound for a reaction without one of its own, and the volume sample. */
internal const val GENERIC_REACTION_SOUND = "generic"

/** Plays the call's sound effects over the call's own audio. One per call, released when it ends. */
internal interface CallSoundEffectRenderer {
    fun play(sound: CallSoundEffect)

    /** Loops [sound] back to back until [stopLoop]; already looping, it carries on. */
    fun startLoop(sound: CallSoundEffect)

    /** Stops the loop at once, mid-sound if need be. */
    fun stopLoop()

    /** Plays [sound] and returns once it has finished, so the call's audio is released after it, not under it. */
    suspend fun playToEnd(sound: CallSoundEffect)

    /** A reaction's sound by web's asset name, `generic` for one there is no file for. False when it plays nothing (037 R10). */
    fun playReaction(sound: String): Boolean

    /** 0 to 1 of the call's level, for every effect but the ringback, those playing included (037 R22, R23). */
    fun setVolume(volume: Float)

    fun release()
}

/**
 * Over [SoundPool]s with the remote voices' usage, so the sounds take their route, their volume stream and their
 * indifference to the ringer, and are cancelled from our microphone by the platform's echo canceller.
 *
 * Reactions have a pool of their own, so a burst of them can never take a stream from a join or a leave.
 */
internal class DefaultCallSoundEffectRenderer(
    private val context: Context,
    volume: Float,
) : CallSoundEffectRenderer {
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val soundPool = SoundPool.Builder().setMaxStreams(CallSoundEffect.entries.size).setAudioAttributes(attributes).build()

    // Above the policy's cap, so a stream outliving its measured length by a few milliseconds is never stolen.
    private val reactionPool = SoundPool.Builder().setMaxStreams(ReactionSoundPolicy.MAXIMUM_PLAYING + 2).setAudioAttributes(attributes).build()
    private val reactionPolicy = ReactionSoundPolicy()

    private val loaded = mutableSetOf<Int>()
    private var loop: CallSoundEffect? = null
    private var loopStreamId = 0
    private var volume = volume.coerceIn(0f, 1f)

    /** Streams that follow [volume], until they end. */
    private val effectStreams = mutableListOf<LiveStream>()
    private val reactionStreams = mutableListOf<LiveStream>()

    private data class ReactionSound(@RawRes val resId: Int, val gain: Float)

    private data class LiveStream(val streamId: Int, val endsAtMs: Long, val gain: Float)

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
    private val reactionSoundIds = REACTION_SOUNDS.mapValues { (_, sound) -> reactionPool.load(context, sound.resId, 1) }
    private val durationsMs = mutableMapOf<Int, Long>()

    @Synchronized
    override fun play(sound: CallSoundEffect) {
        val streamId = soundPool.play(soundIds.getValue(sound), volume, volume, 1, 0, 1f)
        if (streamId == 0) {
            Timber.w("ElementCall: $sound sound not played, not loaded yet")
            return
        }
        val now = SystemClock.elapsedRealtime()
        effectStreams.removeAll { it.endsAtMs <= now }
        effectStreams += LiveStream(streamId, now + durationMs(sound.resId), gain = 1f)
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

    // The ringback is the call still ringing, not an effect: it keeps its level whatever the effects volume (037 R23).
    private fun playLoop(sound: CallSoundEffect) {
        loopStreamId = soundPool.play(soundIds.getValue(sound), RINGBACK_VOLUME, RINGBACK_VOLUME, 1, LOOP_FOREVER, 1f)
    }

    override suspend fun playToEnd(sound: CallSoundEffect) {
        play(sound)
        delay(durationMs(sound.resId))
    }

    @Synchronized
    override fun playReaction(sound: String): Boolean {
        val name = sound.takeIf { it in REACTION_SOUNDS } ?: GENERIC_REACTION_SOUND
        val sound = REACTION_SOUNDS.getValue(name)
        val now = SystemClock.elapsedRealtime()
        if (!reactionPolicy.tryStart(now, durationMs(sound.resId))) return false
        val level = volume * sound.gain * ReactionSoundPolicy.headroom(reactionPolicy.playingCount(now))
        val streamId = reactionPool.play(reactionSoundIds.getValue(name), level, level, 1, 0, 1f)
        if (streamId == 0) {
            Timber.w("ElementCall: reaction sound $name not played, not loaded yet")
            return false
        }
        reactionStreams.removeAll { it.endsAtMs <= now }
        reactionStreams += LiveStream(streamId, now + durationMs(sound.resId), sound.gain)
        applyReactionVolume(now)
        return true
    }

    @Synchronized
    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        val now = SystemClock.elapsedRealtime()
        effectStreams.removeAll { it.endsAtMs <= now }
        effectStreams.forEach { soundPool.setVolume(it.streamId, this.volume, this.volume) }
        applyReactionVolume(now)
    }

    private fun applyReactionVolume(now: Long) {
        reactionStreams.removeAll { it.endsAtMs <= now }
        val share = volume * ReactionSoundPolicy.headroom(reactionPolicy.playingCount(now))
        reactionStreams.forEach { reactionPool.setVolume(it.streamId, share * it.gain, share * it.gain) }
    }

    private fun durationMs(@RawRes resId: Int): Long = durationsMs.getOrPut(resId) {
        runCatchingExceptions {
            context.resources.openRawResourceFd(resId).use { fd ->
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
    }

    override fun release() {
        soundPool.release()
        reactionPool.release()
    }

    private companion object {
        /** Half the call's volume, web's default (030 R24). */
        const val RINGBACK_VOLUME = 0.5f
        const val LOOP_FOREVER = -1

        /** Longer than any of web's end sounds, for a file the platform cannot measure. */
        const val FALLBACK_DURATION_MS = 3_000L

        /**
         * Web's reaction sounds by asset name, pinned in docs/sounds.md, each with the gain that brings it to the join sound's
         * loudness (-29 LUFS, EBU R128): as sent, a dog's bark is 13 dB louder than a join and distorts the speaker. Never
         * above 1, which `SoundPool` cannot play; `dog`, too short to gate, is measured by its RMS.
         */
        val REACTION_SOUNDS = mapOf(
            GENERIC_REACTION_SOUND to ReactionSound(R.raw.element_call_reaction_generic, gain = 0.33f),
            "party" to ReactionSound(R.raw.element_call_reaction_party, gain = 0.49f),
            "clap" to ReactionSound(R.raw.element_call_reaction_clap, gain = 0.34f),
            "dog" to ReactionSound(R.raw.element_call_reaction_dog, gain = 0.22f),
            "cat" to ReactionSound(R.raw.element_call_reaction_cat, gain = 1.00f),
            "lightbulb" to ReactionSound(R.raw.element_call_reaction_lightbulb, gain = 0.42f),
            "crickets" to ReactionSound(R.raw.element_call_reaction_crickets, gain = 1.00f),
            "deer" to ReactionSound(R.raw.element_call_reaction_deer, gain = 0.21f),
            "rock" to ReactionSound(R.raw.element_call_reaction_rock, gain = 0.22f),
            "wave" to ReactionSound(R.raw.element_call_reaction_wave, gain = 0.58f),
            "baduntss" to ReactionSound(R.raw.element_call_reaction_baduntss, gain = 0.43f),
            "bell" to ReactionSound(R.raw.element_call_reaction_bell, gain = 0.87f),
        )
    }
}
