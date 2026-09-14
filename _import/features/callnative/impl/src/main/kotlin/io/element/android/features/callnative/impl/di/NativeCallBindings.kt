/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.callnative.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import io.element.android.features.callnative.impl.receivers.NativeCallActionReceiver
import io.element.android.features.callnative.impl.services.NativeCallForegroundService

@ContributesTo(AppScope::class)
interface NativeCallBindings {
    fun inject(service: NativeCallForegroundService)
    fun inject(receiver: NativeCallActionReceiver)
}
