/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.audio

/**
 * Claims and releases system audio focus for the call, so that other apps duck and the host's own
 * players do not talk over it.
 *
 * A host that coordinates focus across several players (Element X's voice messages and media viewer
 * share one implementation) supplies its own; the default in `element-call` asks for voice
 * communication focus and nothing else.
 */
interface AudioFocus {
    /**
     * Request audio focus for the call.
     * @param onFocusLost invoked when focus is lost for good, or transiently.
     */
    fun requestAudioFocus(onFocusLost: () -> Unit)

    /** Release the audio focus. */
    fun releaseAudioFocus()
}
