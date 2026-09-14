/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

// Temporary: widget-driver stopgap, see `docs/FEEDBACK.md`, "Widget-driver stopgap".

package io.element.android.call.matrix.temporary.widget

import io.element.android.call.matrix.ElementCallTemporaryApi
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
import org.matrix.rustcomponents.sdk.WidgetSettings
import org.matrix.rustcomponents.sdk.makeWidgetDriver
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * The SDK's widget driver behind [WidgetDriver]. Element X's `RustWidgetDriver` with the recv-loop
 * hardening the spike gave it: a channel rather than a shared flow, so nothing is dropped before a
 * collector arrives and closing it tells the collector the driver is gone.
 */
@ElementCallTemporaryApi
internal class SdkWidgetDriver(
    widgetSettings: WidgetSettings,
    private val room: Room,
    private val widgetCapabilitiesProvider: WidgetCapabilitiesProvider,
) : WidgetDriver {
    private val incoming = Channel<String>(Channel.UNLIMITED)
    override val incomingMessages: Flow<String> = incoming.receiveAsFlow()

    private val driverAndHandle = makeWidgetDriver(widgetSettings)
    private var receiveMessageJob: Job? = null

    private var isRunning = AtomicBoolean(false)

    override val id: String = widgetSettings.widgetId

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
