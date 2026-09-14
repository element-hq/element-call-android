/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrixrtc.impl.bridge

import io.element.android.libraries.matrix.api.core.RoomId

/**
 * Why a [MatrixRtcRoomBridge] operation failed.
 */
internal sealed class MatrixRtcBridgeException(message: String) : Exception(message) {
    /**
     * The homeserver answered with an error. [errcode] is the Matrix error code (`M_FORBIDDEN`...), null
     * when the failure did not come from the homeserver's JSON body.
     */
    class MatrixApi(
        val errcode: String?,
        val httpStatus: Int?,
        message: String,
    ) : MatrixRtcBridgeException(message)

    /** No answer within the bridge's timeout. */
    class Timeout(what: String) : MatrixRtcBridgeException("$what timed out")

    /** The bridge is not running: not started yet, stopped, or its transport died. */
    class NotRunning(roomId: RoomId) : MatrixRtcBridgeException("No running bridge for $roomId")

    /** The transport answered with something the bridge cannot read. */
    class InvalidResponse(detail: String) : MatrixRtcBridgeException(detail)

    /** This bridge implementation cannot perform the operation at all. */
    class NotSupported(what: String) : MatrixRtcBridgeException("$what is not supported by this bridge")
}
