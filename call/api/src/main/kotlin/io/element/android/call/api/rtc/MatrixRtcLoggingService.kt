/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc

/**
 * Logging for the `matrix-rust-rtc` core.
 *
 * The configuration is recorded up front but only applied once the native library is actually
 * loaded, on the first native call. Applying it eagerly would pull `libmatrix_rtc_ffi.so` - and the
 * statically linked libwebrtc - into every app start, whether or not a native call ever happens.
 */
interface MatrixRtcLoggingService {
    /**
     * Record the configuration to apply when the core starts. Only the first configuration takes
     * effect: the core installs a process-wide subscriber and will not replace it.
     */
    fun setConfiguration(configuration: MatrixRtcLoggingConfiguration)
}

/**
 * @param logLevel baseline level, applied to every target with no override in [filter].
 * @param filter `RUST_LOG` style per-target overrides, comma separated. Targets are Rust module
 * paths matched by prefix; the roots are `matrix_rtc_core`, `matrix_rtc_media`,
 * `matrix_rtc_livekit` and `matrix_rtc_ffi`, plus `livekit`, `libwebrtc` and `webrtc_sys` from the
 * SFU and WebRTC stacks.
 * @param writesToLogcat when true, the core writes to logcat itself under the `matrix-rtc` tag
 * (`adb logcat -s matrix-rtc`), with the Rust module path prepended to each message. When false,
 * core logging stays off entirely.
 */
data class MatrixRtcLoggingConfiguration(
    val logLevel: MatrixRtcLogLevel,
    val writesToLogcat: Boolean,
    val filter: String = DEFAULT_FILTER,
) {
    companion object {
        /**
         * Holds down the parts that log per frame, so that [logLevel] governs the two we actually
         * read: `matrix_rtc_core` for the membership projection and `matrix_rtc_ffi` for the
         * commands the core asks us to send.
         *
         * `livekit` is at info rather than warn: it is where the SFU connection and ICE progress is
         * reported, and a media session that never connects says nothing at warn. `webrtc_sys` and
         * `libwebrtc` are the ones that log per frame, so they stay down - `libwebrtc` is where the
         * `peer_connection_factory: frame_crypto_transformer` flood comes from, and it is a separate
         * crate from `webrtc_sys`, so holding only the latter down leaves it at the baseline level.
         *
         * Useful additions while chasing something specific:
         * - `matrix_rtc_core::session=trace` - the membership projection, when the roster looks wrong
         * - `matrix_rtc_core::encryption=debug` - key distribution
         * - `libwebrtc=info` - back to the per-frame crypto transformer state
         */
        const val DEFAULT_FILTER =
            "matrix_rtc_media=debug,matrix_rtc_livekit=debug,livekit=info,libwebrtc=warn,webrtc_sys=warn"
    }
}

/** The core's log levels, as the host chooses them. */
enum class MatrixRtcLogLevel {
    ERROR,
    WARN,
    INFO,
    DEBUG,
    TRACE,
}
