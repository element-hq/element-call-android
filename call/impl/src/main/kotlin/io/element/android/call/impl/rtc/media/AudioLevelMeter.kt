/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc.media

import io.element.android.call.api.rtc.MatrixRtcAudioLevel
import timber.log.Timber
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Turns a stream of PCM16 frames into something an eye can follow, and measures whether the loop
 * feeding it is keeping up with real time.
 *
 * Frames arrive every [AudioFormat.FRAME_DURATION_MS], far too fast to publish one by one, so the
 * loudest reading of a [WINDOW_MS] window is published instead. The frame counter goes with it: a
 * level of zero means silence, a counter that stops moving means the frames themselves stopped.
 *
 * The logcat line reports the loudest window since the previous line rather than the window it
 * happens to land on. Sampling one 100 ms window every 5 seconds reads as silence whenever the
 * speaker paused for breath, which looks exactly like a dead microphone.
 *
 * Not thread safe - each stream has its own meter, fed from that stream's single reader loop.
 *
 * @param nanoTime injectable so the realtime ratio can be tested without waiting in real time.
 */
internal class AudioLevelMeter(
    private val label: String,
    private val nanoTime: () -> Long = System::nanoTime,
    private val onLevel: (MatrixRtcAudioLevel) -> Unit,
) {
    private var frameCount = 0L
    private var framesInWindow = 0
    private var windowPeak = 0f
    private var windowCount = 0L
    private var logPeak = 0f

    /** Start of the interval the next [realtimeRatio] will be measured over, and its frame mark. */
    private var intervalStartNanos = 0L
    private var intervalStartFrames = 0L

    /**
     * Audio moved per second of wall clock over the last interval, 1f being real time. Held rather
     * than recomputed per window because a 100 ms sample of it is far too noisy to read.
     */
    private var realtimeRatio: Float? = null

    /**
     * @param frame one frame's PCM16 as little-endian bytes - the form the FFI carries and the form
     * both device APIs take, so this is the frame's own memory rather than a copy or a view over it.
     * Wrapping it to read shorts would allocate on a path that runs a hundred times a second per
     * stream, which is exactly the pressure the audio loops must not add.
     */
    fun onFrame(frame: ByteArray) {
        if (frameCount == 0L) intervalStartNanos = nanoTime()
        frameCount++
        windowPeak = maxOf(windowPeak, levelOf(frame))
        if (++framesInWindow < FRAMES_PER_WINDOW) return

        onLevel(
            MatrixRtcAudioLevel(
                level = windowPeak,
                frameCount = frameCount,
                realtimeRatio = realtimeRatio,
            )
        )
        // Also on logcat: on an emulator, or with the screen off, the logs are all we have. At info
        // so that turning the log level down to quiet the SDK does not take the meter with it.
        logPeak = maxOf(logPeak, windowPeak)
        if (++windowCount % WINDOWS_PER_LOG == 0L) {
            val now = nanoTime()
            realtimeRatio = ratioOver(frames = frameCount - intervalStartFrames, nanos = now - intervalStartNanos)
            intervalStartNanos = now
            intervalStartFrames = frameCount
            Timber.i(
                "MatrixRTC: audio $label - $frameCount frames, level ${"%.2f".format(logPeak)}, " +
                    "realtime ${realtimeRatio?.let { "%.2f".format(it) } ?: "unknown"}x"
            )
            logPeak = 0f
        }
        framesInWindow = 0
        windowPeak = 0f
    }

    /**
     * Frames are a fixed 10 ms of audio each, so the audio they represent divided by the wall clock
     * they took is how close the loop is running to real time.
     */
    private fun ratioOver(frames: Long, nanos: Long): Float? {
        if (nanos <= 0L) return null
        return (frames * AudioFormat.FRAME_DURATION_MS * NANOS_PER_MILLI / nanos.toDouble()).toFloat()
    }

    private fun levelOf(frame: ByteArray): Float {
        val sampleCount = frame.size / Short.SIZE_BYTES
        if (sampleCount <= 0) return 0f
        var sumOfSquares = 0.0
        for (index in 0 until sampleCount) {
            // Little-endian int16, assembled here rather than through a ShortBuffer view so the
            // frame needs no wrapper. The low byte is unsigned, the high byte carries the sign.
            val low = frame[index * 2].toInt() and 0xFF
            val high = frame[index * 2 + 1].toInt()
            val sample = ((high shl 8) or low) / FULL_SCALE
            sumOfSquares += sample * sample
        }
        val rms = sqrt(sumOfSquares / sampleCount)
        if (rms <= 0.0) return 0f
        // Straight amplitude would leave speech in the bottom tenth of the bar. Decibels spread it
        // out the way an ear hears it, with everything below the floor treated as silence.
        val decibels = 20.0 * log10(rms)
        return ((decibels - FLOOR_DB) / -FLOOR_DB).toFloat().coerceIn(0f, 1f)
    }

    private companion object {
        const val FULL_SCALE = 32_768.0
        const val FLOOR_DB = -60.0
        const val WINDOW_MS = 100
        const val FRAMES_PER_WINDOW = WINDOW_MS / AudioFormat.FRAME_DURATION_MS
        const val NANOS_PER_MILLI = 1_000_000L

        /** Every 5 seconds, at one window per 100 ms. */
        const val WINDOWS_PER_LOG = 50
    }
}
