/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

// Temporary: widget-driver stopgap, see `libraries/rustrtc/FEEDBACK.md`, "Widget-driver stopgap".

package io.element.android.libraries.matrixrtc.impl.bridge.widget

import io.element.android.libraries.matrixrtc.impl.bridge.MatrixRtcToDeviceMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter

/**
 * Fans to-device messages from whichever room bridges are live into session-long streams.
 *
 * Only needed because a widget driver is per room and per call, while the RTC core subscribes to
 * to-device messages once per Matrix session. Messages sent while no bridge is live are lost; peers
 * re-send their keys when they see us join, so this is acceptable for a stopgap.
 */
internal class ToDeviceRelay {
    // Buffered so a burst of keys does not stall the bridge that received them; nothing is dropped
    // while a subscriber exists, and with no subscriber there is nobody to deliver to anyway.
    private val messages = MutableSharedFlow<MatrixRtcToDeviceMessage>(extraBufferCapacity = 64)

    /** Messages of the given types, for as long as the flow is collected, whichever bridge delivers them. */
    fun subscribe(eventTypes: List<String>): Flow<MatrixRtcToDeviceMessage> {
        val types = eventTypes.toSet()
        return messages.filter { it.eventType in types }
    }

    suspend fun publish(message: MatrixRtcToDeviceMessage) {
        messages.emit(message)
    }
}
