/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc.media

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
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
import kotlin.math.min

/** The camera [CameraVideoCapture] will open, and the format chosen for it. */
internal data class PreparedCamera(
    val deviceName: String,
    val format: CameraCaptureFormat,
)

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
 * clever about. `MatrixRtcMediaSession.setCameraEnabled` mutes the track at the transport instead.
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
    private var camera: PreparedCamera? = null

    private val frontFacing = AtomicBoolean(true)

    /** Every frame's short edge is brought down to this, the declared one. Written before capture starts, read on the camera thread. */
    @Volatile
    private var outputShortEdge = CameraCaptureFormat.TARGET_SHORT_EDGE

    /** Whether the camera currently capturing faces the user, so a self view knows to mirror. */
    val isFrontFacing: Boolean get() = frontFacing.get()

    private val publisher = VideoFramePublisher(label = "camera", framesPerLog = FRAMES_PER_LOG, onFrame = onFrame)

    private val enumerator by lazy { Camera2Enumerator(context) }

    /**
     * Pick the camera to open and its format, without opening it. The track has to be published
     * with [CameraCaptureFormat.output] before [start], because the declared size sets the layers.
     *
     * @return null when the device has no camera.
     */
    fun prepare(): PreparedCamera? {
        val deviceNames = enumerator.deviceNames
        // Front first, because a call is a conversation. Falls back to whatever exists rather than
        // refusing: a device with only a back camera can still take part.
        val deviceName = deviceNames.firstOrNull { enumerator.isFrontFacing(it) } ?: deviceNames.firstOrNull()
        if (deviceName == null) {
            Timber.w("MatrixRTC: no camera on this device, not capturing video")
            return null
        }
        return formatFor(deviceName)?.let { PreparedCamera(deviceName, it) }
    }

    /** Open [camera] and start publishing to [track]. */
    fun start(track: FfiLocalTrack, camera: PreparedCamera) {
        if (capturer != null) return
        frontFacing.set(enumerator.isFrontFacing(camera.deviceName))

        val egl = EglBase.create()
        // The helper needs a GL context because toI420() on a texture frame is a GPU operation - the
        // camera hands us an OpenGL texture, and this is what reads it back as pixels.
        val helper = SurfaceTextureHelper.create("MatrixRtcCameraCapture", egl.eglBaseContext)
        if (helper == null) {
            Timber.w("MatrixRTC: could not create a surface texture helper, not capturing video")
            egl.release()
            return
        }

        val cameraCapturer = enumerator.createCapturer(camera.deviceName, cameraEvents)
        this.track = track
        this.camera = camera
        outputShortEdge = min(camera.format.output.width, camera.format.output.height)
        eglBase = egl
        surfaceTextureHelper = helper
        capturer = cameraCapturer
        publisher.reset()

        val candidate = camera.format.candidate
        cameraCapturer.initialize(helper, context, capturerObserver)
        // Exactly a size the camera offers, so libwebrtc's nearest-size match cannot land on another shape.
        cameraCapturer.startCapture(candidate.width, candidate.height, CameraCaptureFormat.FRAME_RATE)
        Timber.i(
            "MatrixRTC: capturing video from ${camera.deviceName} (front facing=${frontFacing.get()}) at " +
                "${candidate.width}x${candidate.height}@${CameraCaptureFormat.FRAME_RATE}, " +
                "published as ${camera.format.output.width}x${camera.format.output.height}"
        )
    }

    /**
     * Swap between the front and back cameras, keeping the same published track and its declared size (R9).
     *
     * @param onDone called with whether the camera now in use faces the user. Not called if the swap
     * fails or if nothing is capturing.
     */
    fun switchCamera(onDone: (isFrontFacing: Boolean) -> Unit = {}) {
        val cameraCapturer = capturer ?: return
        val current = camera ?: return
        val deviceNames = enumerator.deviceNames
        val nextName = deviceNames.firstOrNull { enumerator.isFrontFacing(it) != enumerator.isFrontFacing(current.deviceName) }
            ?: deviceNames.getOrNull((deviceNames.indexOf(current.deviceName) + 1) % deviceNames.size)
            ?: return
        val next = formatFor(nextName)?.let { PreparedCamera(nextName, it) } ?: return
        cameraCapturer.switchCamera(
            object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                    frontFacing.set(isFrontCamera)
                    camera = next
                    val candidate = next.format.candidate
                    // libwebrtc reopens with the previous request, which this camera may only match
                    // by a size of another shape.
                    val previous = current.format.candidate
                    if (candidate.width != previous.width || candidate.height != previous.height) {
                        cameraCapturer.changeCaptureFormat(candidate.width, candidate.height, CameraCaptureFormat.FRAME_RATE)
                    }
                    Timber.i("MatrixRTC: switched to the ${if (isFrontCamera) "front" else "back"} camera at ${candidate.width}x${candidate.height}")
                    onDone(isFrontCamera)
                }

                override fun onCameraSwitchError(errorDescription: String?) {
                    Timber.w("MatrixRTC: could not switch camera: $errorDescription")
                }
            },
            nextName,
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
        camera = null
    }

    private fun formatFor(deviceName: String): CameraCaptureFormat? {
        val candidates = runCatchingExceptions { enumerator.getSupportedFormats(deviceName).orEmpty() }
            .onFailure { Timber.w(it, "MatrixRTC: could not list the formats of camera $deviceName") }
            .getOrDefault(emptyList())
            // The frame rate range is in thousandths of a frame per second.
            .map { CameraCaptureFormat.Candidate(it.width, it.height, it.framerate.max / 1000) }
            .distinct()
        val nativeAspect = sensorAspect(deviceName)
        val formats = candidates.joinToString { "${it.width}x${it.height}@${it.maxFrameRate}" }
        Timber.d("MatrixRTC: camera $deviceName, sensor aspect $nativeAspect, formats $formats")
        val format = CameraCaptureFormat.choose(candidates, nativeAspect ?: DEFAULT_SENSOR_ASPECT)
        if (format == null) Timber.w("MatrixRTC: camera $deviceName offers no format, not capturing video")
        return format
    }

    /** The active array's long edge over its short edge: the shape of the camera's full field of view (R6). */
    private fun sensorAspect(deviceName: String): Float? = runCatchingExceptions {
        val cameraManager = context.getSystemService(CameraManager::class.java)
        val activeArray = cameraManager.getCameraCharacteristics(deviceName).get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        activeArray?.takeIf { it.width() > 0 && it.height() > 0 }?.let { maxOf(it.width(), it.height()).toFloat() / minOf(it.width(), it.height()) }
    }.getOrNull()

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
            val track = track ?: return
            val buffer = frame.buffer
            val size = CameraCaptureFormat.scaledToShortEdge(buffer.width, buffer.height, outputShortEdge)
            if (size.width == buffer.width && size.height == buffer.height) {
                publisher.publish(track, frame)
                return
            }
            // The layers were declared for this size, and LiveKit scales whatever arrives by the
            // declared factors. On a texture this only changes the transform; toI420 reads back the smaller size.
            val scaled = VideoFrame(buffer.cropAndScale(0, 0, buffer.width, buffer.height, size.width, size.height), frame.rotation, frame.timestampNs)
            try {
                publisher.publish(track, scaled)
            } finally {
                scaled.release()
            }
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
        const val FRAMES_PER_LOG = CameraCaptureFormat.FRAME_RATE * 5L

        /** Nearly every phone sensor's, for a camera that does not report its active array. */
        const val DEFAULT_SENSOR_ASPECT = 4f / 3f
    }
}
