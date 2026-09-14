/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrixrtc.impl

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrixrtc.api.MatrixRtcService
import io.element.android.libraries.matrixrtc.api.MatrixRtcServiceProvider
import java.util.concurrent.ConcurrentHashMap

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultMatrixRtcServiceProvider(
    private val dispatchers: CoroutineDispatchers,
    /** For the camera, at the far end of service -> session -> call. Nothing else here needs one. */
    @ApplicationContext private val context: Context,
) : MatrixRtcServiceProvider {
    /**
     * One service - and so one RTC session manager - per Matrix session. Entries live as long as
     * the process: the manager holds no server connection of its own, and dropping it while a call
     * is running would tear the call down.
     */
    private val services = ConcurrentHashMap<SessionId, MatrixRtcService>()

    override fun provide(client: MatrixClient): MatrixRtcService {
        return services.getOrPut(client.sessionId) {
            RustMatrixRtcService(client = client, dispatchers = dispatchers, context = context)
        }
    }
}
