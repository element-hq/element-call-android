/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalCoroutinesApi::class)

package io.element.android.call.tests.testutils

import io.element.android.call.api.ElementCallDispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Create a [ElementCallDispatchers] instance for testing.
 *
 * @param useUnconfinedTestDispatcher If true, use [UnconfinedTestDispatcher] for all dispatchers.
 * If false, use [StandardTestDispatcher] for all dispatchers.
 */
fun TestScope.testCoroutineDispatchers(
    useUnconfinedTestDispatcher: Boolean = false,
): ElementCallDispatchers = when (useUnconfinedTestDispatcher) {
    true -> ElementCallDispatchers(
        io = UnconfinedTestDispatcher(testScheduler),
        computation = UnconfinedTestDispatcher(testScheduler),
        main = UnconfinedTestDispatcher(testScheduler),
    )
    false -> ElementCallDispatchers(
        io = StandardTestDispatcher(testScheduler),
        computation = StandardTestDispatcher(testScheduler),
        main = StandardTestDispatcher(testScheduler),
    )
}
