/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrixrtc.api

/**
 * A transport a MatrixRTC session can be carried over (MSC4195).
 *
 * Transport discovery is deliberately not part of the RTC core, so the app queries
 * `GET /_matrix/client/v1/rtc/transports` itself and passes the result in when joining.
 */
sealed interface MatrixRtcTransport {
    data class LiveKit(val serviceUrl: String) : MatrixRtcTransport

    data class Unsupported(val type: String) : MatrixRtcTransport
}
