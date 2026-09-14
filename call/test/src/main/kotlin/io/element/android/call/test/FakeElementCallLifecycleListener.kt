/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.test

import io.element.android.call.api.ElementCallData
import io.element.android.call.api.ElementCallLifecycleListener
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Records what the call reports. [isAppInForeground] is writable so a test can send the app to the
 * background.
 */
class FakeElementCallLifecycleListener(
    isAppInForeground: Boolean = true,
) : ElementCallLifecycleListener {
    override val isAppInForeground = MutableStateFlow(isAppInForeground)

    val startedCalls = mutableListOf<ElementCallData>()
    val joinedCalls = mutableListOf<ElementCallData>()
    val endedCalls = mutableListOf<ElementCallData?>()

    override fun onCallStarted(callData: ElementCallData) {
        startedCalls += callData
    }

    override fun onCallJoined(callData: ElementCallData) {
        joinedCalls += callData
    }

    override fun onCallEnded(callData: ElementCallData?) {
        endedCalls += callData
    }
}
