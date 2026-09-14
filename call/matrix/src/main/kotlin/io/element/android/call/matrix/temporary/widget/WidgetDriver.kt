/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

// Temporary: widget-driver stopgap, see `docs/FEEDBACK.md`, "Widget-driver stopgap".

package io.element.android.call.matrix.temporary.widget

import io.element.android.call.matrix.ElementCallTemporaryApi
import kotlinx.coroutines.flow.Flow

/**
 * The host's end of the SDK widget API: relays the widget API messages between a widget and the Matrix room
 * it is embedded in, in both directions. Here the "widget" is [WidgetMatrixBridge], in-process.
 *
 * Copied from Element X's `MatrixWidgetDriver`, so the bridge and its tests are the spike's. The driver
 * owns SDK resources and must be closed once the widget goes away.
 */
@ElementCallTemporaryApi
internal interface WidgetDriver : AutoCloseable {
    /** The id of the widget this driver serves, matching the one in its settings. */
    val id: String

    /**
     * Messages from the room to the widget, as raw widget API JSON strings.
     *
     * Messages received before anything collects are kept, not dropped. The flow completes once the
     * driver has stopped, which is how a collector learns that no answer will ever come again. Meant
     * for a single collector.
     */
    val incomingMessages: Flow<String>

    /**
     * Start the driver. Returns as soon as it is running: the driver's loops are launched into the
     * calling coroutine's scope and live as long as it does, so call this from a coroutine whose
     * lifetime is the one the driver should have. Calling it again while it is running does nothing.
     */
    suspend fun run()

    /**
     * Forwards a message from the widget to the room.
     *
     * @param message the raw widget API message emitted by the widget.
     * @return false once the driver is no longer running, in which case the message went nowhere.
     */
    suspend fun send(message: String): Boolean
}
