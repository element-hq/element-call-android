/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import com.sun.jna.Pointer
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import uniffi.matrix_rtc_ffi.FfiVideoPlane
import uniffi.matrix_rtc_ffi.FfiVideoRotation
import uniffi.matrix_rtc_ffi.VideoFrameRef

/**
 * Wrap a decoded frame's planes where they already are, without copying a pixel.
 *
 * This used to copy twice per plane - `data(plane)` marshals native memory into a `ByteArray`, and
 * that was repacked into a direct `ByteBuffer` because a direct buffer is the only thing a GL
 * renderer can take without copying again. At VGA that was about 460 KB of fresh allocation thirty
 * times a second per stream, and `FEEDBACK.md` item 14 measured the result: our frame path 53% of
 * the process's CPU and garbage collection another 29%, against 2% for the hardware codec.
 *
 * The contract that makes the zero-copy route safe is now documented in the library's `frames.rs`,
 * and this depends on all of it:
 *
 * - **Lifetime is the object.** The address is valid for as long as any reference to the
 *   [VideoFrameRef] is alive, and is stable across calls - so the frame holds the ref and closes it
 *   in `onRelease`, and the buffers stay valid for exactly as long as the frame does.
 * - **No thread affinity.** Read from any thread, unsynchronised; release from any thread. The planes
 *   are written once at construction and never mutated.
 * - **The one rule is ordering:** every read must happen before the release, and the release must
 *   happen exactly once. Ordering is the caller's problem, and the reason `MatrixRtcVideoFrame` is
 *   now a resource rather than a value.
 *
 * The strides are the source's own rather than normalised to the width, because with nothing being
 * repacked there is nothing to normalise them during - a decoded plane's rows are usually further
 * apart than the width, and `JavaI420Buffer.wrap` takes the stride precisely so it does not care.
 *
 * **This takes ownership of the [VideoFrameRef].** The caller must not close it; releasing the
 * returned frame is what closes it, and doing both is a double free.
 */
internal fun VideoFrameRef.mapZeroCopy(): MatrixRtcVideoFrame {
    val width = width().toInt()
    val height = height().toInt()
    return MatrixRtcVideoFrame(
        width = width,
        height = height,
        rotationDegrees = rotation().toDegrees(),
        timestampUs = timestampUs(),
        dataY = planeBuffer(FfiVideoPlane.Y),
        strideY = stride(FfiVideoPlane.Y).toInt(),
        dataU = planeBuffer(FfiVideoPlane.U),
        strideU = stride(FfiVideoPlane.U).toInt(),
        dataV = planeBuffer(FfiVideoPlane.V),
        strideV = stride(FfiVideoPlane.V).toInt(),
        onRelease = { close() },
    )
}

/**
 * One plane's native memory as a direct [java.nio.ByteBuffer], owning nothing.
 *
 * JNA rather than anything in the JDK, because there is no pure-Java way to wrap an arbitrary address
 * in a direct buffer - `Pointer.getByteBuffer` is the whole reason this is possible at all. JNA is
 * already on the classpath as the FFI's own binding layer, so this adds no dependency.
 */
private fun VideoFrameRef.planeBuffer(plane: FfiVideoPlane): java.nio.ByteBuffer {
    val address = planePtr(plane).toLong()
    val length = planeLen(plane).toLong()
    return Pointer(address).getByteBuffer(0, length)
}

internal fun FfiVideoRotation.toDegrees(): Int = when (this) {
    FfiVideoRotation.DEG0 -> 0
    FfiVideoRotation.DEG90 -> 90
    FfiVideoRotation.DEG180 -> 180
    FfiVideoRotation.DEG270 -> 270
}
