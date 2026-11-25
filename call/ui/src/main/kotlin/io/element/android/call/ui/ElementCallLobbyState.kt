/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.runtime.Immutable
import io.element.android.call.api.ElementCallLobbyJoinLabel

/**
 * What the lobby draws (feature-hq 045), on top of the call screen's own state. Sealed, so nothing can draw a label, a
 * count or a camera state while the core is still reading the call (R59).
 */
@Immutable
sealed interface ElementCallLobbyState {
    /** The core is reading the call: no label, no count, no camera state, no output; only the settings and Close work. */
    data object Loading : ElementCallLobbyState

    data class Ready(
        val joinLabel: ElementCallLobbyJoinLabel,
        /** The core's count, drawn as is (R43); shown only above zero. */
        val memberCount: Int,
        /** The call emptied after the lobby showed somebody in it (R57). */
        val isCallEndedNoticeShown: Boolean,
        /** Why Join is disabled, or null when it is enabled (R27, R51). */
        val joinUnavailableReason: ElementCallLobbyJoinBlock?,
        /** What the preview says about our camera (R28, R50). */
        val camera: ElementCallLobbyCamera,
        /** The refused microphone's button: ask again where the system still asks, else the app's settings (R27). */
        val microphoneAction: ElementCallLobbyMicrophoneAction?,
    ) : ElementCallLobbyState
}

enum class ElementCallLobbyJoinBlock {
    MICROPHONE_REFUSED,
}

enum class ElementCallLobbyCamera {
    ON,
    OFF,
    NOT_ALLOWED,
}

enum class ElementCallLobbyMicrophoneAction {
    ALLOW,
    OPEN_SETTINGS,
}
