/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import org.matrix.rtc.MatrixRtc
import uniffi.matrix_rtc_ffi.uniffiEnsureInitialized

/**
 * Entry point for the `matrix-rust-rtc` native library.
 *
 * Nothing in this module may touch the FFI before [ensureInitialized] has returned. It is public,
 * the one exception to "everything in `…impl.rtc` is internal", because `call/ui`'s renderer touches
 * libwebrtc natives (`JavaI420Buffer.allocate`) and the sample app must therefore run it from
 * `Application.onCreate()` even though it never opens a call.
 */
object MatrixRtcNative {
    private var initialized = false

    /**
     * @throws Throwable if the native library cannot be loaded. Deliberately propagated: without it
     * libwebrtc has no `JavaVM*`, and the first media call would abort the process instead of
     * failing. Callers turn this into a `Result`.
     */
    @Synchronized
    fun ensureInitialized() {
        if (initialized) return
        // The AAR owns native loading: this is the only loader that runs the library's `JNI_OnLoad`,
        // which hands libwebrtc its `JavaVM*` and class loader. The generated uniffi bindings open
        // the library with JNA instead, and JNA is a plain `dlopen` that never triggers it - so
        // going straight to uniffi leaves media to die on a null dereference inside `RtcRuntime()`.
        // Idempotent, so it stays correct if the AAR is also initialised elsewhere.
        MatrixRtc.initialize()
        uniffiEnsureInitialized()
        initialized = true
    }
}
