/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc.media

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import io.element.android.call.impl.util.runCatchingExceptions
import livekit.org.webrtc.CapturerObserver
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.ScreenCapturerAndroid
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoFrame
import timber.log.Timber
import uniffi.matrix_rtc_ffi.FfiLocalTrack

/**
 * Captures the screen and feeds it to a published RTC track, mirroring [CameraVideoCapture].
 *
 * The capturer is libwebrtc's `ScreenCapturerAndroid` out of the bundled jar, which wraps
 * `MediaProjection` in the same [livekit.org.webrtc.VideoCapturer] interface the camera uses - so
 * from `onFrameCaptured` onwards this is the camera path exactly, via [VideoFramePublisher].
 *
 * Three things make it different from the camera, and all three are platform rules rather than
 * choices:
 *
 * - **It needs a token from an Activity.** `MediaProjectionManager.createScreenCaptureIntent()` has
 *   to be launched for a result, and the `Intent` that comes back is what [start] takes. It is
 *   single-use: once spent it cannot be replayed, so stopping and sharing again means asking again.
 * - **The foreground service has to be up first.** From Android 14 a `mediaProjection` foreground
 *   service must already be running when the projection is claimed, which happens inside
 *   [ScreenCapturerAndroid.initialize]. The caller is responsible for that ordering.
 * - **The user can stop it from outside the app**, from the cast notification, and nothing in the
 *   capturer interface reports that. [MediaProjection.Callback] does, which is why [start] takes
 *   [onStopped] - without it the UI would go on claiming to share a screen that is no longer being
 *   captured.
 *
 * There is no local preview: [onFrame] is not plumbed, because the person sharing their screen is
 * looking at it.
 */
internal class ScreenVideoCapture(private val context: Context) {
    private var eglBase: EglBase? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var track: FfiLocalTrack? = null

    private val publisher = VideoFramePublisher(label = "screen", framesPerLog = FRAMES_PER_LOG)

    /**
     * Claim the projection and start publishing to [track].
     *
     * @param permissionData the `Intent` an Activity got back from
     * `MediaProjectionManager.createScreenCaptureIntent()`.
     * @param onStopped called if the projection ends without us asking - the user stopping it from
     * the system UI, or the platform revoking it. Called on a binder thread.
     * @return whether capture started. False means nothing was claimed and nothing needs stopping.
     */
    fun start(track: FfiLocalTrack, permissionData: Intent, onStopped: () -> Unit): Boolean {
        if (capturer != null) return true

        val egl = EglBase.create()
        // The helper needs a GL context because toI420() on a texture frame is a GPU operation -
        // the projection hands us an OpenGL texture, and this is what reads it back as pixels.
        val helper = SurfaceTextureHelper.create("MatrixRtcScreenCapture", egl.eglBaseContext)
        if (helper == null) {
            Timber.w("MatrixRTC: could not create a surface texture helper, not capturing the screen")
            egl.release()
            return false
        }

        val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                Timber.i("MatrixRTC: screen capture stopped from outside the app")
                onStopped()
            }
        }
        val screenCapturer = ScreenCapturerAndroid(permissionData, projectionCallback)
        val (width, height) = captureSize()

        // Claiming the projection happens inside initialize(), so this is where a missing
        // mediaProjection foreground service surfaces - as a SecurityException on Android 14+,
        // which would otherwise take the process down.
        val started = runCatchingExceptions {
            screenCapturer.initialize(helper, context, capturerObserver)
            screenCapturer.startCapture(width, height, VideoFormat.SCREEN_FPS)
        }.onFailure {
            Timber.w(it, "MatrixRTC: could not start screen capture")
        }.isSuccess

        if (!started) {
            runCatchingExceptions { screenCapturer.dispose() }
            helper.dispose()
            egl.release()
            return false
        }

        this.track = track
        eglBase = egl
        surfaceTextureHelper = helper
        capturer = screenCapturer
        publisher.reset()
        Timber.i("MatrixRTC: capturing the screen at ${width}x$height@${VideoFormat.SCREEN_FPS}")
        return true
    }

    /**
     * Release the projection. Safe to call when nothing is running, and safe to call twice.
     *
     * `stopCapture` blocks until the capture thread has actually stopped, which is what makes it
     * safe to dispose the helper afterwards - the alternative is a frame arriving on a released GL
     * context.
     */
    fun stop() {
        capturer?.let { screenCapturer ->
            runCatchingExceptions { screenCapturer.stopCapture() }
                .onFailure { Timber.w(it, "MatrixRTC: screen capture did not stop cleanly") }
            runCatchingExceptions { screenCapturer.dispose() }
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
            if (success) {
                Timber.i("MatrixRTC: screen capture started")
            } else {
                Timber.w("MatrixRTC: screen capture failed to start")
            }
        }

        override fun onCapturerStopped() {
            Timber.i("MatrixRTC: screen capture stopped after ${publisher.publishedFrameCount} frame(s)")
        }

        override fun onFrameCaptured(frame: VideoFrame) {
            publisher.publish(track ?: return, frame)
        }
    }

    /**
     * The size of the virtual display, which decides what everyone else sees.
     *
     * Scaled down from the real screen rather than captured at native resolution: a modern phone is
     * around 1080x2400, and `FEEDBACK.md` item 14 records that our per-frame repacking already
     * dominates CPU at VGA. Capturing five times the pixels of a camera frame would make the phone
     * the bottleneck long before the network was.
     *
     * The aspect ratio is kept, because a stretched screen share is worse than a small one, and both
     * dimensions are forced even - I420 has half-resolution chroma planes, so an odd dimension has no
     * exact representation.
     */
    private fun captureSize(): Pair<Int, Int> {
        val (screenWidth, screenHeight) = screenSize()
        val longest = maxOf(screenWidth, screenHeight)
        if (longest <= 0) return VideoFormat.SCREEN_MAX_EDGE to VideoFormat.SCREEN_MAX_EDGE
        val scale = minOf(1f, VideoFormat.SCREEN_MAX_EDGE.toFloat() / longest)
        return makeEven((screenWidth * scale).toInt()) to makeEven((screenHeight * scale).toInt())
    }

    private fun screenSize(): Pair<Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return 0 to 0
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    private fun makeEven(value: Int) = (value / 2) * 2

    private companion object {
        /** Five seconds at the requested frame rate. */
        const val FRAMES_PER_LOG = VideoFormat.SCREEN_FPS * 5L
    }
}
