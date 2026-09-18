/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import android.app.Application
import io.element.android.call.impl.rtc.MatrixRtcNative
import timber.log.Timber

class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        // The video renderer wraps frames in libwebrtc's buffers, whose JNI half lives in the RTC
        // library. It has to be loaded before the first tile draws, even though no call is ever joined.
        MatrixRtcNative.ensureInitialized()
    }
}
