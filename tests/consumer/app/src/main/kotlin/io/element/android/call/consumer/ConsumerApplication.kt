/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.consumer

import android.app.Application
import io.element.android.call.impl.rtc.MatrixRtcNative

class ConsumerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // What every host does before the first tile: load the core, whose JNI half the renderer needs.
        MatrixRtcNative.ensureInitialized()
    }
}
