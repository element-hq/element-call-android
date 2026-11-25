/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import android.content.Context
import io.element.android.call.api.ElementCallDispatchers
import io.element.android.call.api.rtc.MatrixRtcLocalCamera
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import io.element.android.call.impl.rtc.media.CameraVideoCapture
import io.element.android.call.impl.rtc.media.PreparedCamera
import io.element.android.call.impl.util.runCatchingExceptions
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.matrix.rtc.FfiLocalTrack
import timber.log.Timber

/** Our camera over libwebrtc's capturer, from the lobby into the call (feature-hq 045 R49). */
internal class RustMatrixRtcLocalCamera(
    context: Context,
    private val dispatchers: ElementCallDispatchers,
) : MatrixRtcLocalCamera {
    /**
     * One frame of slack and drop the oldest, because the camera must never wait for a renderer. A dropped frame costs a
     * stale self view for a thirtieth of a second; blocking would stall the camera thread and, with it, publishing.
     */
    private val _frames = MutableSharedFlow<MatrixRtcVideoFrame>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val frames: Flow<MatrixRtcVideoFrame> = _frames

    private val _isCapturing = MutableStateFlow(false)
    override val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _isFrontFacing = MutableStateFlow(true)
    override val isFrontFacing: StateFlow<Boolean> = _isFrontFacing.asStateFlow()

    private val capture = CameraVideoCapture(context, onFrame = { _frames.tryEmit(it) })

    /** Start, stop and attach never interleave: a stop racing a start would leave the camera open with nothing to close it. */
    private val lock = Any()

    /** The camera open and its format, or null while stopped: what the session publishes a track with. */
    val prepared: PreparedCamera? get() = capture.camera

    override suspend fun start(): Result<Unit> = withContext(dispatchers.io) {
        synchronized(lock) {
            runCatchingExceptions {
                if (capture.isCapturing) return@runCatchingExceptions
                // The capturer's classes come from the RTC AAR's native library.
                MatrixRtcFfi.ensureInitialized()
                val camera = checkNotNull(capture.prepare()) { "no camera to open" }
                check(capture.start(camera)) { "the camera did not open" }
                // Read back rather than assumed: a device with no front camera starts on the back one.
                _isFrontFacing.value = capture.isFrontFacing
                _isCapturing.value = true
            }.onFailure { Timber.w(it, "MatrixRTC: could not start the camera") }
        }
    }

    override suspend fun switchCamera(): Result<Unit> = synchronized(lock) {
        runCatchingExceptions {
            // Asynchronous by nature, the camera has to close and another open, so the facing lands in the callback.
            capture.switchCamera { isFrontFacing -> _isFrontFacing.value = isFrontFacing }
        }.onFailure { Timber.w(it, "MatrixRTC: failed to switch camera") }
    }

    /** Publish the running capture to [track]: nothing reopens (R49). */
    fun attach(track: FfiLocalTrack) = synchronized(lock) { capture.attach(track) }

    fun detach() = synchronized(lock) { capture.detach() }

    override fun stop() = synchronized(lock) {
        if (capture.isCapturing) Timber.i("MatrixRTC: stopping the camera")
        capture.stop()
        _isCapturing.value = false
    }
}
