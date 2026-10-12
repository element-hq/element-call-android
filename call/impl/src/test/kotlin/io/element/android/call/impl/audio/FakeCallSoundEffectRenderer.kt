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

    override fun release() {
        releaseCount++
    }
}
