/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrixrtc.api

import io.element.android.libraries.matrix.api.MatrixClient

/**
 * Hands out the [MatrixRtcService] for a Matrix session.
 *
 * The RTC session manager is per Matrix session, but calls are driven from an Activity that only
 * has access to app-scoped dependencies, so the service is reached through this provider rather
 * than injected directly. One service per session, reused across calls.
 */
interface MatrixRtcServiceProvider {
    fun provide(client: MatrixClient): MatrixRtcService
}
