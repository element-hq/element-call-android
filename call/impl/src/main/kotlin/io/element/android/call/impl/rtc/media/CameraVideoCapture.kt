/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc.media

import android.content.Context
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import io.element.android.call.impl.util.runCatchingExceptions
import livekit.org.webrtc.Camera2Enumerator
import livekit.org.webrtc.CameraVideoCapturer
import livekit.org.webrtc.CapturerObserver
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoFrame
import org.matrix.rtc.FfiLocalTrack
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures the camera and feeds it to a published RTC track, one I420 frame at a time.
 *
 * The camera stack comes from `libwebrtc.jar`, which ships inside the RTC AAR with its JNI half
 * compiled into the same `.so` the FFI loads - so `MatrixRtcFfi.ensureInitialized()` is what makes
 * these classes work, and nothing here may run before it has. Using it rather than CameraX is what
 * buys us [VideoFrame.Buffer.toI420]: the conversion from whatever the device produced into the
 * exact three-plane layout the FFI wants, done in native code we would otherwise have to write.
 *
 * Unlike [AudioCapture] this owns no coroutine. The capturer runs its own camera and GL threads and
 * calls us back on them, and `captureVideo` does not suspend, so a frame's whole journey happens on
 * the thread it arrived on.
 *
 * Also unlike [AudioCapture], there is no mute: stopping is the only way to stop sending, because
 * the indicator light staying on while the UI says the camera is off is not a thing worth being
 * clever about. `MatrixRtcCall.setCameraEnabled` mutes the track at the transport instead.
 *
 * [onFrame] receives a copy of every captured frame, for a self view. Called on a camera thread.
 */
internal class CameraVideoCapture(
    private val context: Context,
    private val onFrame: (MatrixRtcVideoFrame) -> Unit = {},
) {
    private var eglBase: EglBase? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var capturer: CameraVideoCapturer? = null
    private var track: FfiLocalTrack? = null

    private val frontFacing = AtomicBoolean(true)

    /** Whether the camera currently capturing faces the user, so a self view knows to mirror. */
    val isFrontFacing: Boolean get() = frontFacing.get()

    private val publisher = VideoFramePublisher(label = "camera", framesPerLog = FRAMES_PER_LOG, onFrame = onFrame)

    /**
     * Open the camera and start publishing to [track].
     *
     * Never throws for the ordinary failures, including a missing
     * [android.Manifest.permission.CAMERA]. That is a real difference from [AudioCapture], where the
     * `AudioRecord` constructor throws immediately: Camera2 opens asynchronously, so a refused
     * permission arrives later as `onCameraError` on the events handler below. A caller that wants
     * to know whether the camera really started has to watch the log or the self view; there is no
     * return value that could tell it.
     */
    fun start(track: FfiLocalTrack) {
        if (capturer != null) return

        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames
        if (deviceNames.isEmpty()) {
            Timber.w("MatrixRTC: no camera on this device, not capturing video")
            return
        }
        // Front first, because a call is a conversation. Falls back to whatever exists rather than
        // refusing: a device with only a back camera can still take part.
        val deviceName = deviceNames.firstOrNull { enumerator.isFrontFacing(it) } ?: deviceNames.first()
        frontFacing.set(enumerator.isFrontFacing(deviceName))

        val egl = EglBase.create()
        // The helper needs a GL context because toI420() on a texture frame is a GPU operation - the
        // camera hands us an OpenGL texture, and this is what reads it back as pixels.
        val helper = SurfaceTextureHelper.create("MatrixRtcCameraCapture", egl.eglBaseContext)
        if (helper == null) {
            Timber.w("MatrixRTC: could not create a surface texture helper, not capturing video")
            egl.release()
            return
        }

        val cameraCapturer = enumerator.createCapturer(deviceName, cameraEvents)
        this.track = track
        eglBase = egl
        surfaceTextureHelper = helper
        capturer = cameraCapturer
        publisher.reset()

        cameraCapturer.initialize(helper, context, capturerObserver)
        cameraCapturer.startCapture(VideoFormat.CAPTURE_WIDTH, VideoFormat.CAPTURE_HEIGHT, VideoFormat.CAPTURE_FPS)
        Timber.i(
            "MatrixRTC: capturing video from $deviceName (front facing=${frontFacing.get()}) at " +
                "${VideoFormat.CAPTURE_WIDTH}x${VideoFormat.CAPTURE_HEIGHT}@${VideoFormat.CAPTURE_FPS}"
        )
    }

    /**
     * Swap between the front and back cameras, keeping the same published track.
     *
     * @param onDone called with whether the camera now in use faces the user. Not called if the swap
     * fails or if nothing is capturing.
     */
    fun switchCamera(onDone: (isFrontFacing: Boolean) -> Unit = {}) {
        val cameraCapturer = capturer ?: return
        cameraCapturer.switchCamera(
            object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                    frontFacing.set(isFrontCamera)
                    Timber.i("MatrixRTC: switched to the ${if (isFrontCamera) "front" else "back"} camera")
                    onDone(isFrontCamera)
                }

                override fun onCameraSwitchError(errorDescription: String?) {
                    Timber.w("MatrixRTC: could not switch camera: $errorDescription")
                }
            }
        )
    }

    /**
     * Release the camera. Safe to call when nothing is running, and safe to call twice.
     *
     * `stopCapture` blocks until the camera thread has actually stopped, which is what makes it safe
     * to dispose the helper afterwards - the alternative is a frame arriving on a released GL
     * context.
     */
    fun stop() {
        capturer?.let { cameraCapturer ->
            runCatchingExceptions { cameraCapturer.stopCapture() }
                .onFailure { Timber.w(it, "MatrixRTC: camera did not stop cleanly") }
            cameraCapturer.dispose()
        }
        capturer = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        eglBase?.release()
        eglBase = null
        track = null
    }

    private val capturerObserver = object : CapturerObserver {
        override fun onCapturerStarted(success: Boolean) {
            // Logged at warn when it fails, because this is where a refused camera permission
            // surfaces and the screen above has no other way to find out.
            if (success) {
                Timber.i("MatrixRTC: camera capture started")
            } else {
                Timber.w("MatrixRTC: camera capture failed to start")
            }
        }

        override fun onCapturerStopped() {
            Timber.i("MatrixRTC: camera capture stopped after ${publisher.publishedFrameCount} frame(s)")
        }

        override fun onFrameCaptured(frame: VideoFrame) {
            publisher.publish(track ?: return, frame)
        }
    }

    private val cameraEvents = object : CameraVideoCapturer.CameraEventsHandler {
        // At warn, and never swallowed: a refused CAMERA permission arrives here as an error string
        // from Camera2 rather than as an exception anyone could catch.
        override fun onCameraError(errorDescription: String?) = Timber.w("MatrixRTC: camera error: $errorDescription")

        override fun onCameraDisconnected() = Timber.w("MatrixRTC: camera disconnected")

        override fun onCameraFreezed(errorDescription: String?) = Timber.w("MatrixRTC: camera froze: $errorDescription")

        override fun onCameraOpening(cameraName: String?) = Timber.d("MatrixRTC: opening camera $cameraName")

        override fun onFirstFrameAvailable() = Timber.i("MatrixRTC: first camera frame available")

        override fun onCameraClosed() = Timber.d("MatrixRTC: camera closed")
    }

    private companion object {
        /** Five seconds at the requested frame rate. */
        const val FRAMES_PER_LOG = VideoFormat.CAPTURE_FPS * 5L
    }
}
