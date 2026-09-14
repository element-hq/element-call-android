/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrixrtc.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.matrixrtc.api.MatrixRtcLoggingConfiguration
import io.element.android.libraries.matrixrtc.api.MatrixRtcLoggingService

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultMatrixRtcLoggingService : MatrixRtcLoggingService {
    override fun setConfiguration(configuration: MatrixRtcLoggingConfiguration) {
        MatrixRtcFfi.setLoggingConfiguration(configuration)
    }
}
