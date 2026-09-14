/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.matrix.temporary.widget

import io.element.android.call.matrix.ElementCallTemporaryApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.UUID

/**
 * The two ends of the widget pipe, as queues: what the driver would say goes in with
 * [givenIncomingMessage], what the host sent comes out of [sentMessages] or [awaitSentMessage].
 */
@ElementCallTemporaryApi
internal class FakeWidgetDriver(
    override val id: String = UUID.randomUUID().toString(),
) : WidgetDriver {
    private val incoming = Channel<String>(Channel.UNLIMITED)
    private val sent = Channel<String>(Channel.UNLIMITED)

    private val _sentMessages = mutableListOf<String>()
    val sentMessages: List<String> = _sentMessages

    var runCalledCount = 0
        private set
    var closeCalledCount = 0
        private set

    /** False once [givenDriverStopped] was called: [send] then reports the driver gone. */
    var isRunning = true
        private set

    override val incomingMessages: Flow<String> = incoming.receiveAsFlow()

    override suspend fun run() {
        runCalledCount++
    }

    override suspend fun send(message: String): Boolean {
        _sentMessages.add(message)
        sent.trySend(message)
        return isRunning
    }

    override fun close() {
        closeCalledCount++
    }

    fun givenIncomingMessage(message: String) {
        incoming.trySend(message)
    }

    /**
     * The driver has stopped: sends report failure and, unless [keepIncomingOpen], the incoming flow
     * completes. Keeping it open is for the case where the host learns of the death from a failed send
     * before the pipe closes.
     */
    fun givenDriverStopped(keepIncomingOpen: Boolean = false) {
        isRunning = false
        if (!keepIncomingOpen) incoming.close()
    }

    /** The next message the host sent, waiting for it if needed. */
    suspend fun awaitSentMessage(): String = sent.receive()
}
