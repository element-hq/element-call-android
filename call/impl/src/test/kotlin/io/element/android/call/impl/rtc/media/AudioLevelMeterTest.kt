/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrixrtc.impl.media

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrixrtc.api.MatrixRtcAudioLevel
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class AudioLevelMeterTest {
    @Test
    fun `nothing is published until a full window of frames has been measured`() {
        val published = mutableListOf<MatrixRtcAudioLevel>()
        val meter = AudioLevelMeter(label = "test", onLevel = published::add)

        repeat(FRAMES_PER_WINDOW - 1) { meter.onFrame(silence()) }
        assertThat(published).isEmpty()

        meter.onFrame(silence())
        assertThat(published).hasSize(1)
        assertThat(published.single().frameCount).isEqualTo(FRAMES_PER_WINDOW)
    }

    @Test
    fun `silence measures zero`() {
        val published = mutableListOf<MatrixRtcAudioLevel>()
        val meter = AudioLevelMeter(label = "test", onLevel = published::add)

        repeat(FRAMES_PER_WINDOW) { meter.onFrame(silence()) }

        assertThat(published.single().level).isEqualTo(0f)
    }

    @Test
    fun `full scale measures one`() {
        val published = mutableListOf<MatrixRtcAudioLevel>()
        val meter = AudioLevelMeter(label = "test", onLevel = published::add)

        repeat(FRAMES_PER_WINDOW) { meter.onFrame(fullScale()) }

        assertThat(published.single().level).isAtLeast(0.99f)
    }

    @Test
    fun `a quieter tone measures between the two`() {
        val published = mutableListOf<MatrixRtcAudioLevel>()
        val meter = AudioLevelMeter(label = "test", onLevel = published::add)

        // -12 dBFS, the amplitude the capture test tone uses. Its RMS lands around -15 dBFS, which
        // is three quarters of the way up a 60 dB scale.
        repeat(FRAMES_PER_WINDOW) { meter.onFrame(tone(amplitude = 8_192.0)) }

        assertThat(published.single().level).isWithin(0.02f).of(0.75f)
    }

    @Test
    fun `the loudest frame of a window is the one published, and the count keeps rising`() {
        val published = mutableListOf<MatrixRtcAudioLevel>()
        val meter = AudioLevelMeter(label = "test", onLevel = published::add)

        // One loud frame among silence: a meter that averaged would hide it.
        meter.onFrame(fullScale())
        repeat(FRAMES_PER_WINDOW - 1) { meter.onFrame(silence()) }
        // A second window, all silent: the peak must not carry over.
        repeat(FRAMES_PER_WINDOW) { meter.onFrame(silence()) }

        assertThat(published[0].level).isAtLeast(0.99f)
        assertThat(published[1].level).isEqualTo(0f)
        assertThat(published.map { it.frameCount }).containsExactly(10L, 20L).inOrder()
    }

    @Test
    fun `the realtime ratio is unknown until a full interval has been measured`() {
        val published = mutableListOf<MatrixRtcAudioLevel>()
        val clock = TestClock()
        val meter = AudioLevelMeter(label = "test", nanoTime = clock::now, onLevel = published::add)

        repeat(FRAMES_PER_WINDOW) {
            meter.onFrame(silence())
            clock.advance(NANOS_PER_FRAME)
        }

        assertThat(published.single().realtimeRatio).isNull()
    }

    @Test
    fun `a loop keeping up with real time measures one`() {
        val published = mutableListOf<MatrixRtcAudioLevel>()
        val clock = TestClock()
        val meter = AudioLevelMeter(label = "test", nanoTime = clock::now, onLevel = published::add)

        // One interval to measure, then one more window so a level is published carrying the result.
        repeat(FRAMES_PER_LOG + FRAMES_PER_WINDOW) {
            meter.onFrame(silence())
            clock.advance(NANOS_PER_FRAME)
        }

        assertThat(published.last().realtimeRatio).isWithin(0.01f).of(1f)
    }

    @Test
    fun `a starving loop measures below one`() {
        val published = mutableListOf<MatrixRtcAudioLevel>()
        val clock = TestClock()
        val meter = AudioLevelMeter(label = "test", nanoTime = clock::now, onLevel = published::add)

        // A quarter longer than real time per frame, which is the shape the Pixel 5 fault had: the
        // loop still moved every frame, it just took 5.9 s to move 5.0 s of audio.
        repeat(FRAMES_PER_LOG + FRAMES_PER_WINDOW) {
            meter.onFrame(silence())
            clock.advance(NANOS_PER_FRAME * 5 / 4)
        }

        assertThat(published.last().realtimeRatio).isWithin(0.01f).of(0.8f)
    }

    private class TestClock {
        private var nanos = 0L
        fun now() = nanos
        fun advance(by: Long) {
            nanos += by
        }
    }

    private fun silence() = frame { 0 }

    private fun fullScale() = frame { Short.MAX_VALUE }

    private fun tone(amplitude: Double) = frame { index ->
        val phase = 2.0 * PI * 440.0 * index / AudioFormat.SAMPLE_RATE
        (sin(phase) * amplitude).toInt().toShort()
    }

    /**
     * Built the way both audio paths hold one: little-endian PCM16 bytes. Written through a short
     * view over that same memory, which is how the capture tone generator writes it, so the test is
     * on the layout the FFI actually uses rather than on one it composed itself.
     */
    private fun frame(sample: (Int) -> Short): ByteArray {
        val bytes = ByteArray(AudioFormat.BYTES_PER_FRAME)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().apply {
            for (index in 0 until limit()) put(index, sample(index))
        }
        return bytes
    }

    private companion object {
        /** 100 ms of 10 ms frames, matching the meter's window. */
        const val FRAMES_PER_WINDOW = 10

        /** 5 seconds of windows, the interval the meter measures the realtime ratio over. */
        const val WINDOWS_PER_LOG = 50
        const val FRAMES_PER_LOG = FRAMES_PER_WINDOW * WINDOWS_PER_LOG
        const val NANOS_PER_FRAME = AudioFormat.FRAME_DURATION_MS * 1_000_000L
    }
}
