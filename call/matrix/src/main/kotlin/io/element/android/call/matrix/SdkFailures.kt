/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.matrix

import io.element.android.call.api.matrix.ElementCallMatrixException
import org.matrix.rustcomponents.sdk.ClientException

/**
 * The SDK's homeserver errors in the call's vocabulary, so that the classifier in `call/impl` reads one
 * error type whichever transport carried the failure. Anything else passes through unchanged.
 */
internal fun Throwable.toMatrixException(): Throwable = when (this) {
    is ClientException.MatrixApi -> ElementCallMatrixException.MatrixApi(
        errcode = code,
        // The bindings do not carry the HTTP status.
        httpStatus = null,
        message = msg,
    )
    else -> this
}

internal fun <T> Result<T>.mapSdkFailure(): Result<T> = fold(
    onSuccess = { Result.success(it) },
    onFailure = { Result.failure(it.toMatrixException()) },
)
