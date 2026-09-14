/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrixrtc.impl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Guards the way `libraries/rustrtc` exposes `matrixrtc-release.aar`: a bare Gradle project publishing the
 * file on its `default` configuration.
 *
 * The Rust SDK AAR has no nested `libs/`, so this is the first time we rely on AGP unpacking one. If the
 * `libs/libwebrtc.jar` assertion fails, the AAR needs its nested jar published as a separate artifact.
 *
 * Class resolution only - loading the native library needs a device.
 */
class MatrixRtcAarClasspathTest {
    @Test
    fun `uniffi bindings are on the classpath`() {
        assertThat(Class.forName("uniffi.matrix_rtc_ffi.RtcSessionManagerHandle")).isNotNull()
        assertThat(Class.forName("uniffi.matrix_rtc_ffi.MediaSession")).isNotNull()
        assertThat(Class.forName("uniffi.matrix_rtc_ffi.MembershipSnapshotSubscription")).isNotNull()
    }

    @Test
    fun `libwebrtc jar nested in the aar is on the classpath`() {
        assertThat(Class.forName("livekit.org.webrtc.EncodedImage")).isNotNull()
    }

    /**
     * Video has no fallback: capture and rendering are both this jar, so losing it is not a
     * degradation but the end of video, and the failure would otherwise happen on a device rather
     * than here. `FEEDBACK.md` item 2 records that upstream has not confirmed the jar is meant to be
     * used by a consumer, which is exactly why this is asserted rather than assumed.
     */
    @Test
    fun `the libwebrtc classes video depends on are all present`() {
        // Capture: enumerate the cameras, open one, and read its GL texture back as pixels.
        assertThat(Class.forName("livekit.org.webrtc.Camera2Enumerator")).isNotNull()
        assertThat(Class.forName("livekit.org.webrtc.SurfaceTextureHelper")).isNotNull()
        assertThat(Class.forName("livekit.org.webrtc.EglBase")).isNotNull()
        // Display: wrap our own I420 planes and draw them.
        assertThat(Class.forName("livekit.org.webrtc.JavaI420Buffer")).isNotNull()
        assertThat(Class.forName("livekit.org.webrtc.EglRenderer")).isNotNull()
        assertThat(Class.forName("livekit.org.webrtc.GlRectDrawer")).isNotNull()
    }

    /**
     * The jar ships no `TextureViewRenderer`, only the `SurfaceView` one, which cannot be animated -
     * so `CallTextureView` builds the equivalent out of [livekit.org.webrtc.EglRenderer] directly.
     * That only works because `EglRenderer` can take a `SurfaceTexture`, which is a narrower thing to
     * depend on than a whole renderer and is worth asserting by itself: losing this overload means
     * falling back to `SurfaceViewRenderer` and losing every tile animation with it.
     */
    @Test
    fun `EglRenderer can draw into a SurfaceTexture`() {
        val method = Class.forName("livekit.org.webrtc.EglRenderer")
            .getMethod("createEglSurface", android.graphics.SurfaceTexture::class.java)
        assertThat(method).isNotNull()
    }
}
