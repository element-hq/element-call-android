/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.test.audio

import io.element.android.call.api.audio.AudioFocus
import io.element.android.call.test.lambdaError

class FakeAudioFocus(
    private val requestAudioFocusResult: (() -> Unit) -> Unit = { lambdaError() },
    private val releaseAudioFocusResult: () -> Unit = { lambdaError() },
    /** Whether the lobby's request is granted at once; false is a phone call holding the audio. */
    var grantsFocus: Boolean = true,
) : AudioFocus {
    override fun requestAudioFocus(onFocusLost: () -> Unit) {
        requestAudioFocusResult(onFocusLost)
    }

    /** The lobby's last request's callbacks, to play a phone call taking the audio and giving it back. */
    var onFocusLost: (() -> Unit)? = null
        private set
    var onFocusGained: (() -> Unit)? = null
        private set

    /** Every lobby request, counted apart from the call's. */
    var lobbyRequestCount = 0
        private set

    override fun requestAudioFocus(onFocusLost: () -> Unit, onFocusGained: () -> Unit): Boolean {
        lobbyRequestCount++
        this.onFocusLost = onFocusLost
        this.onFocusGained = onFocusGained
        requestAudioFocusResult(onFocusLost)
        return grantsFocus
    }

    override fun releaseAudioFocus() {
        releaseAudioFocusResult()
    }
}
