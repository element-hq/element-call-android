/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.audio

internal class FakeCallSoundEffectRenderer : CallSoundEffectRenderer {
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
