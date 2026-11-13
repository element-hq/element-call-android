/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc

/**
 * The codec our camera and screen share are published with (feature-hq 043 R15). Only one is published: a receiver
 * that cannot decode it sees no video from us (R15b).
 */
enum class MatrixRtcVideoCodec {
    /** The core's default, [autoCodec]. */
    AUTO,
    VP8,
    H264,
    VP9,
    AV1,
    ;

    companion object {
        /**
         * What the core publishes [AUTO] with, for the "Auto (VP8)" label (043 R15a). The core documents its default but
         * does not expose it, so it is stated here, once: a core that changes its default changes this line.
         */
        val autoCodec: MatrixRtcVideoCodec = VP8
    }
}
