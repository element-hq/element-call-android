/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

/**
 * Entry point for the `matrix-rust-rtc` native library, for callers outside this module.
 *
 * Nothing may touch the FFI before [ensureInitialized] has returned. It is public, the one exception
 * to "everything in `…impl.rtc` is internal", because `call/ui`'s renderer touches libwebrtc natives
 * (`JavaI420Buffer.allocate`) and the sample app must therefore run it from `Application.onCreate()`
 * even though it never opens a call. The stack does it itself before the core is built.
 */
object MatrixRtcNative {
    /**
     * @throws Throwable if the native library cannot be loaded. Deliberately propagated: without it
     * libwebrtc has no `JavaVM*`, and the first media call would abort the process instead of
     * failing. Callers turn this into a `Result`.
     */
    fun ensureInitialized() = MatrixRtcFfi.ensureInitialized()
}
