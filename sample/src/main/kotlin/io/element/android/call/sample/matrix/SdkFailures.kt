/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample.matrix

import org.matrix.rtc.FfiBackendException
import org.matrix.rustcomponents.sdk.ClientException

/** A failure as the core classifies it, which it does on the Matrix errcode and the HTTP status alone. */
internal fun Throwable.toFfiBackendException(): FfiBackendException = when (this) {
    is FfiBackendException -> this
    // The bindings do not carry the HTTP status.
    is ClientException.MatrixApi -> FfiBackendException.Failed(errcode = code, status = null, reason = msg)
    else -> FfiBackendException.Failed(errcode = null, status = null, reason = message ?: javaClass.simpleName)
}
