/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.audio

internal class FakeCallSoundEffectRenderer(initialVolume: Float = 0.5f) : CallSoundEffectRenderer {
    /** The level the effects play at now. */
    var volume = initialVolume
        private set

    /** Every reaction sound asked for, in order, played or not. */
    val reactions = mutableListOf<String>()

    /** What [playReaction] answers: false as when eight already play. */
    var isReactionVoiceFree = true

    override fun playReaction(sound: String): Boolean {
        reactions += sound
        return isReactionVoiceFree
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
    }

    val played = mutableListOf<CallSoundEffect>()
    var releaseCount = 0

    override fun play(sound: CallSoundEffect) {
        played += sound
    }

    /** The sound looping now, or null. */
    var looping: CallSoundEffect? = null
        private set

    /** Every sound played to its end, in order; suspends on [endSoundGate] when one is set. */
    val playedToEnd = mutableListOf<CallSoundEffect>()
    var endSoundGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override fun startLoop(sound: CallSoundEffect) {
        looping = sound
    }

    override fun stopLoop() {
        looping = null
    }

    override suspend fun playToEnd(sound: CallSoundEffect) {
        playedToEnd += sound
        endSoundGate?.await()
    }

    override fun release() {
        releaseCount++
    }
}
