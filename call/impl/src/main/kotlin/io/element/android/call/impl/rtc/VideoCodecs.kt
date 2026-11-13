/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import io.element.android.call.api.rtc.MatrixRtcVideoCodec
import org.matrix.rtc.FfiVideoCodecPreference

internal fun MatrixRtcVideoCodec.toFfi(): FfiVideoCodecPreference = when (this) {
    MatrixRtcVideoCodec.AUTO -> FfiVideoCodecPreference.AUTO
    MatrixRtcVideoCodec.VP8 -> FfiVideoCodecPreference.VP8
    MatrixRtcVideoCodec.H264 -> FfiVideoCodecPreference.H264
    MatrixRtcVideoCodec.VP9 -> FfiVideoCodecPreference.VP9
    MatrixRtcVideoCodec.AV1 -> FfiVideoCodecPreference.AV1
}

internal fun FfiVideoCodecPreference.toApi(): MatrixRtcVideoCodec = when (this) {
    FfiVideoCodecPreference.AUTO -> MatrixRtcVideoCodec.AUTO
    FfiVideoCodecPreference.VP8 -> MatrixRtcVideoCodec.VP8
    FfiVideoCodecPreference.H264 -> MatrixRtcVideoCodec.H264
    FfiVideoCodecPreference.VP9 -> MatrixRtcVideoCodec.VP9
    FfiVideoCodecPreference.AV1 -> MatrixRtcVideoCodec.AV1
}
