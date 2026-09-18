/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * Can be used to catch exceptions in a block of code and return a [Result].
 * If the block throws a [CancellationException], it will be rethrown.
 * If it throws any other exception, it will be wrapped in a [Result.failure].
 *
 * [Error]s are not caught by this function, as they are not meant to be caught in normal application flow.
 *
 * Copied from Element X's `libraries/core`; every module that needs it carries its own internal copy.
 */
internal inline fun <T> runCatchingExceptions(
    block: () -> T
): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/**
 * Can be used to catch exceptions in a block of code and return a [Result].
 * If the block throws a [CancellationException], it will be rethrown.
 * If it throws any other exception, it will be wrapped in a [Result.failure].
 */
internal inline fun <T, R> T.runCatchingExceptions(
    block: T.() -> R
): Result<R> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
