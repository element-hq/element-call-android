/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.matrix

import io.element.android.call.api.rtc.id.RoomId

/**
 * Why an [ElementCallMatrixTransport] or [ElementCallMatrixRoom] operation failed.
 *
 * Implementations map their own failures to these so that the call stack reads one vocabulary: a
 * homeserver's `M_FORBIDDEN` on a delayed event, for instance, retires the dead man's switch for the
 * session, while a timeout is retried.
 */
sealed class ElementCallMatrixException(message: String) : Exception(message) {
    /**
     * The homeserver answered with an error. [errcode] is the Matrix error code (`M_FORBIDDEN`...), null
     * when the failure did not come from the homeserver's JSON body.
     */
    class MatrixApi(
        val errcode: String?,
        val httpStatus: Int?,
        message: String,
    ) : ElementCallMatrixException(message)

    /** No answer within the implementation's timeout. */
    class Timeout(what: String) : ElementCallMatrixException("$what timed out")

    /** The room is not open: not opened yet, closed, or its transport died. */
    class NotRunning(roomId: RoomId) : ElementCallMatrixException("No open room for $roomId")

    /** The transport answered with something the implementation cannot read. */
    class InvalidResponse(detail: String) : ElementCallMatrixException(detail)

    /** This implementation cannot perform the operation at all. */
    class NotSupported(what: String) : ElementCallMatrixException("$what is not supported by this transport")
}
