/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample.lab

import io.element.android.call.api.ElementCallController
import io.element.android.call.api.ElementCallData
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.sample.SampleLiveCall
import org.matrix.rustcomponents.sdk.TaskHandle
import timber.log.Timber

/**
 * A real call in the lab: the session's controller over the real core, on the shipping screen, with the phone's own
 * camera and microphone. Closing it leaves the session, and so the stack, to the next call.
 */
class SampleLabCall(private val session: SampleLabSession) : SampleLiveCall {
    private var connectivity: TaskHandle? = null

    override val controller: ElementCallController get() = session.stack.controller

    override fun start() {
        connectivity = session.observeConnectivity { isOnline ->
            Timber.i("Lab: the sync is ${if (isOnline) "running again" else "offline"}; reporting it")
            controller.reportConnectivity(isOnline)
        }
        // The lab's group room shows the lobby, and joins without choosing: the call's own intent applies (045 R18).
        controller.startCall(ElementCallData(roomId = RoomId(session.roomId), intent = null, isStartingCall = false, isDm = false))
    }

    override fun close() {
        connectivity?.cancel()
        connectivity = null
    }
}
