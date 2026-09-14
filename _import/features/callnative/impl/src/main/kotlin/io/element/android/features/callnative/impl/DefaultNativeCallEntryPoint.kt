/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.callnative.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.call.api.CallData
import io.element.android.features.callnative.api.NativeCallEntryPoint
import timber.log.Timber

@ContributesBinding(AppScope::class)
class DefaultNativeCallEntryPoint(
    private val controller: NativeCallController,
) : NativeCallEntryPoint {
    /**
     * Starts the call in place, without an Activity of its own.
     *
     * This used to launch `NativeCallActivity` into a separate task, mirroring how the Element Call
     * WebView is hosted. Nothing needs a window any more: the call is held by [NativeCallController]
     * and drawn by `NativeCallHost` inside the app's own Compose tree, which is what lets it be
     * minimized into a bar instead of only ever being full screen.
     */
    override fun startCall(callData: CallData) {
        Timber.i("NativeCall: starting call for ${callData.roomId}")
        controller.startCall(callData)
    }
}
