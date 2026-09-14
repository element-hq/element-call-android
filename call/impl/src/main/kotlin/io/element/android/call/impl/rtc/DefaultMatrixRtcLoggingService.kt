/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.call.api.rtc.MatrixRtcLoggingConfiguration
import io.element.android.call.api.rtc.MatrixRtcLoggingService

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultMatrixRtcLoggingService : MatrixRtcLoggingService {
    override fun setConfiguration(configuration: MatrixRtcLoggingConfiguration) {
        MatrixRtcFfi.setLoggingConfiguration(configuration)
    }
}
