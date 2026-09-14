/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.callnative.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrixrtc.api.MatrixRtcVideoFrame
import kotlinx.coroutines.flow.Flow
import livekit.org.webrtc.JavaI420Buffer
import livekit.org.webrtc.VideoFrame
import timber.log.Timber

/**
 * Draws one member's video, sized entirely by its caller.
 *
 * The renderer is libwebrtc's, out of the jar bundled in the RTC AAR, which is what makes this
 * cheap: it uploads the three planes as textures and converts on the GPU, so nothing here touches a
 * pixel. Building an [android.graphics.Bitmap] per frame instead would mean a colour conversion in
 * Kotlin thirty times a second.
 *
 * Sizing is deliberately left to the caller: the diagnostics list wants a fixed-height row while the
 * call grid decides the shape of every tile itself, and a size baked in here made the second
 * impossible.
 *
 * @param frames the stream to draw. Nothing is shown until the first frame arrives.
 * @param isMirrored whether to flip horizontally. True for our own front camera and nothing else.
 */
@Composable
fun CallVideoRenderer(
    frames: Flow<MatrixRtcVideoFrame>,
    isMirrored: Boolean,
    modifier: Modifier = Modifier,
    /**
     * Told about every frame drawn, for the debug overlay. Counts only - it must never write Compose
     * state, or reading the numbers would cost more than producing them.
     */
    frameCounter: TileFrameCounter? = null,
) {
    // Previews and Paparazzi have no GL context, and the renderer's init would try to make one.
    // A placeholder keeps every screenshot test that renders this screen alive.
    if (LocalInspectionMode.current) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "video",
                style = ElementTheme.typography.fontBodyXsRegular,
                color = ElementTheme.colors.textSecondary,
            )
        }
        return
    }

    val handle = remember { RendererHandle() }
    var isAttached by remember { mutableStateOf(false) }

    LaunchedEffect(isAttached, frames) {
        if (!isAttached) return@LaunchedEffect
        frames.collect { frame ->
            frameCounter?.onFrame(frame.width, frame.height)
            handle.render(frame)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            CallTextureView(context).apply {
                handle.attach(this)
                isAttached = true
            }
        },
        update = { it.setMirror(isMirrored) },
        // The renderer holds a GL thread and a surface, and releasing it is the only way to give
        // them back. Called exactly once, when the view leaves the composition.
        onRelease = { handle.release() },
    )
}

/**
 * Owns the renderer, and makes releasing it exclusive with drawing to it.
 *
 * Without this the two race. `onRelease` runs the moment a tile leaves the composition and tears
 * down the renderer's GL thread, while the coroutine collecting frames may be inside `onFrame` -
 * cancelling that collector is not synchronous, so "the effect was cancelled" is not the same as
 * "the effect has stopped". A frame arriving a microsecond late then draws to a released renderer.
 *
 * `CallTileLayout` narrowed how often this happens - a tile now survives being promoted to the
 * spotlight and demoted again, where before each move disposed one renderer and built another - but
 * it did not remove it. A member turning their camera off, leaving, or the call ending all still
 * dispose a tile while frames may be in flight.
 *
 * The lock is uncontended in the normal case and `onFrame` only posts to the render thread, so this
 * costs a few nanoseconds per frame and cannot block the caller for long.
 */
private class RendererHandle {
    private val lock = Any()
    private var renderer: CallTextureView? = null

    fun attach(renderer: CallTextureView) = synchronized(lock) {
        this.renderer = renderer
    }

    fun render(frame: MatrixRtcVideoFrame) = synchronized(lock) {
        renderer?.render(frame)
    }

    fun release() = synchronized(lock) {
        renderer?.release()
        renderer = null
    }
}

/**
 * Wrap our frame's planes as a libwebrtc buffer and hand it over.
 *
 * No copy: [JavaI420Buffer.wrap] takes the direct buffers as they are, which is the whole reason
 * [MatrixRtcVideoFrame] carries direct buffers rather than byte arrays. The null release callback is
 * right because the memory belongs to the frame object and goes when it is collected - there is
 * nothing to hand back.
 *
 * The `release()` matters even so: it drops the wrapper's own reference, and libwebrtc's renderer
 * retains the frame while it draws.
 */
private fun CallTextureView.render(frame: MatrixRtcVideoFrame) {
    // The frame's planes are the core's own memory now, not a copy, and drawing happens on the GL
    // thread well after this function returns - so the frame has to be kept alive past the end of
    // this call.
    //
    // A refused retain means the frame was already freed before it got here, which is only ever a
    // bug in whoever released it: something downstream of the release is buffering. It is logged
    // rather than passed over because the symptom is otherwise indistinguishable from a slow network
    // - frames arrive, nothing draws, and the only clue is a frame rate. That is exactly how the
    // first version of this shipped, with `flowOn` buffering 64 frames past their own release.
    if (!frame.retain()) {
        Timber.w("MatrixRTC: dropped a video frame that was already released - something is buffering past its release")
        return
    }
    val buffer = JavaI420Buffer.wrap(
        frame.width,
        frame.height,
        frame.dataY,
        frame.strideY,
        frame.dataU,
        frame.strideU,
        frame.dataV,
        frame.strideV,
        // libwebrtc reference-counts the buffer and calls this when it is finished with it, which is
        // exactly the handover this needs: our reference lives precisely as long as the renderer's.
        // Passing null - as this did while the planes were copies we owned - would free the core's
        // memory while the GL thread was still reading it.
        frame::release,
    )
    val videoFrame = VideoFrame(buffer, frame.rotationDegrees, frame.timestampUs * NANOS_PER_MICRO)
    try {
        onFrame(videoFrame)
    } finally {
        // Drops the wrapper's own reference. The renderer has taken its own by now if it needs one,
        // and when the last goes the callback above releases the frame.
        videoFrame.release()
    }
}

private const val NANOS_PER_MICRO = 1_000L
