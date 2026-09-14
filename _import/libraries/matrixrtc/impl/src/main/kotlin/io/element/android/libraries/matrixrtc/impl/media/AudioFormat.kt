/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrixrtc.impl.media

/**
 * The PCM format we capture and publish in.
 *
 * 48 kHz mono matches what the RTC stack works in internally, so nothing has to resample. The
 * frame length is the usual WebRTC 10 ms tick.
 */
internal object AudioFormat {
    const val SAMPLE_RATE = 48_000
    const val CHANNEL_COUNT = 1
    const val FRAME_DURATION_MS = 10
    const val SAMPLES_PER_FRAME = SAMPLE_RATE / 1000 * FRAME_DURATION_MS

    /** The FFI carries a frame as little-endian int16 bytes, which is also what the platform wants. */
    const val BYTES_PER_FRAME = SAMPLES_PER_FRAME * Short.SIZE_BYTES

    /**
     * Frames of slack to floor the device buffers at, on both the way in and the way out.
     *
     * `getMinBufferSize` is the smallest size the platform will accept, not a size that leaves any
     * room: a track built at exactly that has to be refilled before it drains or it under-runs, and
     * an under-run is audible as a crackle. 100 ms absorbs a GC pause - measured at up to 2.3 ms on
     * a loaded Pixel 5 - and a missed scheduling slot without adding any per-frame cost.
     */
    const val BUFFER_FRAMES = 10

    /** Floor for both `AudioRecord` and `AudioTrack` buffers, see [BUFFER_FRAMES]. */
    const val MIN_DEVICE_BUFFER_BYTES = BYTES_PER_FRAME * BUFFER_FRAMES
}
