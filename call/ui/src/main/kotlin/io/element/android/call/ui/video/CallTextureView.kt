/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui.video

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.EglRenderer
import livekit.org.webrtc.GlRectDrawer
import livekit.org.webrtc.VideoFrame
import timber.log.Timber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A video sink that draws into the view hierarchy rather than beside it.
 *
 * The jar bundled in the RTC AAR ships only [livekit.org.webrtc.SurfaceViewRenderer], and a
 * `SurfaceView` cannot be animated: it is a separate hardware surface composited by SurfaceFlinger
 * *behind* the window, so it ignores alpha, ignores rounded-corner clipping, and its content arrives
 * at its old bounds for a frame or two after the view is moved or resized. That is visible in the
 * logs as `BLASTBufferQueue: rejecting buffer` whenever a tile changes size, and on screen as a tile
 * that jumps to its destination while everything around it animates.
 *
 * A `TextureView` is an ordinary view drawing into a texture, so it scales, fades and clips like any
 * other. It costs one more copy through the GPU and about a frame of latency, which is the right
 * trade at the handful of tiles a call has and would not be at twenty.
 *
 * Built out of [EglRenderer] - the piece `SurfaceViewRenderer` itself is built on - via
 * [EglRenderer.createEglSurface], which takes a [SurfaceTexture] precisely for this.
 *
 * Scaling is centre-crop: [EglRenderer.setLayoutAspectRatio] samples a sub-rectangle of the frame so
 * it fills the view. `SurfaceViewRenderer` got aspect-fit by measuring *itself* to the video's
 * aspect ratio, which a view whose bounds are decided by the layout above it cannot do - and filling
 * is what the tile design wants anyway.
 */
internal class CallTextureView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private val eglRenderer = EglRenderer(RENDERER_NAME)

    init {
        // Null shared context: this renderer makes its own. Nothing hands it a texture - every frame
        // it sees is already I420 - so there is no context to share with the capturer.
        eglRenderer.init(null, EglBase.CONFIG_PLAIN, GlRectDrawer())
        surfaceTextureListener = this
    }

    fun onFrame(frame: VideoFrame) = eglRenderer.onFrame(frame)

    fun setMirror(isMirrored: Boolean) = eglRenderer.setMirror(isMirrored)

    /** Gives back the GL thread. The view is unusable afterwards. */
    fun release() {
        surfaceTextureListener = null
        eglRenderer.release()
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        eglRenderer.createEglSurface(surfaceTexture)
        updateLayoutAspectRatio(width, height)
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        updateLayoutAspectRatio(width, height)
    }

    /**
     * Returning true hands the [SurfaceTexture] back to the platform to destroy, so the GL thread has
     * to be finished with it first - hence the wait. Skipping it releases a texture the renderer is
     * still drawing to, which is a native crash rather than a glitch.
     *
     * The timeout is a bound on how long the UI thread can be held here, not an expectation: the GL
     * thread only has to finish the frame it is on. Carrying on after it expires is still the better
     * option, since blocking the UI thread indefinitely is worse than a torn frame.
     */
    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        val released = CountDownLatch(1)
        eglRenderer.releaseEglSurface { released.countDown() }
        if (!released.await(SURFACE_RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Timber.w("MatrixRTC: timed out waiting for the renderer to release its surface")
        }
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit

    private fun updateLayoutAspectRatio(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        eglRenderer.setLayoutAspectRatio(width.toFloat() / height.toFloat())
    }
}

private const val RENDERER_NAME = "CallTileData"
private const val SURFACE_RELEASE_TIMEOUT_MS = 500L
