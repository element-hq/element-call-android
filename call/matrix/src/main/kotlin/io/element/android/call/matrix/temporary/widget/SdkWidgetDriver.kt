/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.widget

import io.element.android.libraries.matrix.api.widget.MatrixWidgetDriver
import io.element.android.libraries.matrix.api.widget.MatrixWidgetSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.WidgetCapabilitiesProvider
import org.matrix.rustcomponents.sdk.makeWidgetDriver
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

class RustWidgetDriver(
    widgetSettings: MatrixWidgetSettings,
    private val room: Room,
    private val widgetCapabilitiesProvider: WidgetCapabilitiesProvider,
) : MatrixWidgetDriver {
    // A channel rather than a shared flow: nothing is dropped while no one collects yet, and closing it
    // is how a collector learns the driver has stopped. One collector at a time.
    private val incoming = Channel<String>(Channel.UNLIMITED)
    override val incomingMessages: Flow<String> = incoming.receiveAsFlow()

    private val driverAndHandle = makeWidgetDriver(widgetSettings.toRustWidgetSettings())
    private var receiveMessageJob: Job? = null

    private var isRunning = AtomicBoolean(false)

    override val id: String = widgetSettings.id

    override suspend fun run() {
        // Don't run the driver if it's already running
        if (!isRunning.compareAndSet(false, true)) {
            return
        }

        val coroutineScope = CoroutineScope(coroutineContext)
        coroutineScope.launch {
            // This call will suspend the coroutine while the driver is running, so it needs to be launched separately
            driverAndHandle.driver.run(room, widgetCapabilitiesProvider)
        }
        receiveMessageJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    // Null means the driver is no longer running: stop rather than spin on it.
                    val message = driverAndHandle.handle.recv() ?: break
                    incoming.send(message)
                }
            } finally {
                incoming.close()
                driverAndHandle.handle.close()
            }
        }
    }

    override suspend fun send(message: String): Boolean {
        return try {
            driverAndHandle.handle.send(message)
        } catch (_: IllegalStateException) {
            // The handle is closed, so the driver is gone.
            false
        }
    }

    override fun close() {
        receiveMessageJob?.cancel()
        driverAndHandle.driver.close()
    }
}
