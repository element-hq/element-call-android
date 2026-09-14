/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc.media

import android.annotation.SuppressLint
import android.media.AudioRecord
import android.media.MediaRecorder
import io.element.android.call.api.rtc.MatrixRtcAudioLevel
import io.element.android.call.impl.util.runCatchingExceptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import uniffi.matrix_rtc_ffi.FfiAudioFrame
import uniffi.matrix_rtc_ffi.FfiLocalTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin
import android.media.AudioFormat as AndroidAudioFormat

/**
 * Captures the microphone and feeds it to a published RTC track, one 10 ms PCM frame at a time.
 *
 * Muting stops frames being handed over rather than publishing silence, so a muted microphone
 * costs no bandwidth. The [AudioRecord] keeps running so unmuting is instant.
 */
internal class AudioCapture(
    private val scope: CoroutineScope,
    onLevel: (MatrixRtcAudioLevel) -> Unit = {},
) {
    private val muted = AtomicBoolean(false)
    private val testTone = AtomicBoolean(false)
    private var recorder: Recorder? = null
    private val meter = AudioLevelMeter(label = "out", onLevel = onLevel)

    /** Phase of the test tone, carried across frames so the sine does not click at frame edges. */
    private var tonePhase = 0.0

    fun setMuted(value: Boolean) {
        muted.set(value)
    }

    /** Replace what the microphone hears with a fixed tone, see `MatrixRtcCall.setAudioTestToneEnabled`. */
    fun setTestToneEnabled(value: Boolean) {
        testTone.set(value)
    }

    /**
     * @throws SecurityException if [android.Manifest.permission.RECORD_AUDIO] has not been granted.
     */
    @SuppressLint("MissingPermission")
    // The read loop has three ways out - a released recorder, a read error and an empty read while
    // the tone is off - and each is its own exit on purpose, so the log can say which.
    @Suppress("LoopWithTooManyJumpStatements")
    fun start(track: FfiLocalTrack) {
        if (recorder != null) return

        val minBufferSize = AudioRecord.getMinBufferSize(
            AudioFormat.SAMPLE_RATE,
            AndroidAudioFormat.CHANNEL_IN_MONO,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) {
            Timber.w("MatrixRTC: audio capture unavailable, getMinBufferSize returned $minBufferSize")
            return
        }
        // A few frames of slack so a slow FFI hand-off does not drop samples.
        val bufferSize = maxOf(minBufferSize, AudioFormat.MIN_DEVICE_BUFFER_BYTES)

        val record = AudioRecord(
            // Gives us the platform's echo cancellation and noise suppression.
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            AudioFormat.SAMPLE_RATE,
            AndroidAudioFormat.CHANNEL_IN_MONO,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Timber.w("MatrixRTC: AudioRecord failed to initialise")
            record.release()
            return
        }
        val newRecorder = Recorder(record)
        newRecorder.startRecording()

        // On a thread of its own at audio priority, see audioDispatcher. Capture had the milder half
        // of the problem - the record buffer holds 100 ms - but it is the same loop on the same pool.
        val dispatcher = audioDispatcher("MatrixRtcAudioIn")
        val newJob = scope.launch(dispatcher) {
            // One frame of PCM16, held as the bytes the FFI takes. The short view is over the same
            // memory, so metering and the test tone cost no conversion and the frame crosses the
            // boundary without being copied or boxed.
            val frame = ByteArray(AudioFormat.BYTES_PER_FRAME)
            val samples = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            var shortReads = 0
            while (isActive) {
                // Stops on a released record rather than reading from one: the read and the release
                // are mutually exclusive, so this can only be RELEASED once the record is really gone.
                val read = newRecorder.read(frame)
                if (read == Recorder.RELEASED) break
                if (read < 0) {
                    Timber.w("MatrixRTC: AudioRecord.read failed with $read")
                    break
                }
                val isToneEnabled = testTone.get()
                if (read < frame.size) {
                    // The device had nothing for us. The tone does not need the device, but it does
                    // need the pacing the read would have given it - and a device that always comes
                    // back empty would otherwise spin this loop flat out.
                    //
                    // Said out loud once, because skipping the meter is the only other trace this
                    // leaves: a frame count stuck at zero, which looks identical to a microphone we
                    // never published at all.
                    if (++shortReads == SHORT_READS_BEFORE_WARNING) {
                        Timber.w(
                            "MatrixRTC: AudioRecord returned $read of ${frame.size} bytes " +
                                "$SHORT_READS_BEFORE_WARNING times running, capture is producing no audio"
                        )
                    }
                    delay(AudioFormat.FRAME_DURATION_MS.toLong())
                    if (!isToneEnabled) continue
                } else {
                    shortReads = 0
                }
                if (isToneEnabled) fillWithTone(samples)

                // Metered before the mute check: knowing the microphone is alive while we are muted
                // is exactly the question a silent call raises. The meter reads the bytes directly;
                // `samples` remains only because the tone generator needs to write shorts.
                meter.onFrame(frame)
                if (muted.get()) continue

                track.captureAudio(
                    FfiAudioFrame(
                        data = frame,
                        sampleRate = AudioFormat.SAMPLE_RATE.toUInt(),
                        numChannels = AudioFormat.CHANNEL_COUNT.toUInt(),
                        samplesPerChannel = AudioFormat.SAMPLES_PER_FRAME.toUInt(),
                    )
                )
            }
        }
        // Closed on completion rather than in stop(): stop does not wait for the loop, and the
        // thread has to outlive the read that stop is letting finish.
        newJob.invokeOnCompletion { dispatcher.close() }
        // Attached before the recorder is reachable, so nothing can release it without a job to
        // cancel - and if stop() already ran, attach cancels the job it was handed.
        newRecorder.attach(newJob)
        recorder = newRecorder
    }

    /**
     * Overwrite a frame with a sine wave. The [AudioRecord] read still paces the loop, so the tone
     * comes out at the same rate as real capture would.
     */
    private fun fillWithTone(samples: ShortBuffer) {
        val step = 2.0 * PI * TONE_HZ / AudioFormat.SAMPLE_RATE
        for (index in 0 until samples.limit()) {
            samples.put(index, (sin(tonePhase) * TONE_AMPLITUDE).toInt().toShort())
            tonePhase += step
            if (tonePhase >= 2.0 * PI) tonePhase -= 2.0 * PI
        }
    }

    fun stop() {
        recorder?.release()
        recorder = null
    }

    /**
     * One microphone, with reading from it and releasing it made mutually exclusive.
     *
     * The same race [AudioPlayback] had on the way out, in the same shape on the way in - and fixed
     * the same way, because it is the same bug. `Job.cancel()` does not wait, and the capture loop
     * blocks inside `AudioRecord.read` in blocking mode, which is a blocking call rather than a
     * suspension point and so cannot be interrupted by cancellation. Release therefore freed the
     * native record while a read was still in flight.
     *
     * That one had to be found by a crash on a three-way call. This one is fixed on the strength of
     * the resemblance rather than a tombstone: the ordering is identical, the window is the same size,
     * and waiting for it to happen on a demo device is not a plan.
     *
     * The lock is held across the read, so release waits for at most one frame - 10 ms of audio -
     * rather than proceeding underneath it.
     */
    private class Recorder(private val record: AudioRecord) {
        private val lock = Any()
        private var job: Job? = null
        private var isReleased = false

        fun attach(job: Job) = synchronized(lock) {
            if (isReleased) job.cancel() else this.job = job
        }

        fun startRecording() = synchronized(lock) {
            if (!isReleased) record.startRecording()
        }

        /** Returns [RELEASED] once the record is gone, which tells the loop to stop. */
        fun read(frame: ByteArray): Int = synchronized(lock) {
            if (isReleased) return RELEASED
            // Caught rather than thrown: capture failing should end capture, not the call and not the
            // process. An unsupervised coroutine throwing here takes the whole app down.
            runCatchingExceptions { record.read(frame, 0, frame.size) }
                .onFailure { Timber.w(it, "MatrixRTC: audio read failed, stopping capture") }
                .getOrDefault(RELEASED)
        }

        fun release() {
            synchronized(lock) {
                if (isReleased) return
                isReleased = true
                job?.cancel()
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    record.stop()
                }
                record.release()
            }
        }

        companion object {
            /**
             * Distinct from every `AudioRecord` error code - which run from -1 to -6 - so "we stopped"
             * is never mistaken for "the device failed" and logged as one.
             */
            const val RELEASED = Int.MIN_VALUE
        }
    }

    private companion object {
        /** One second of frames: long enough that a single slow read on startup stays quiet. */
        const val SHORT_READS_BEFORE_WARNING = 100

        /** A 440 Hz tone at roughly -12 dBFS: obvious on a meter, and obvious in an earpiece. */
        const val TONE_HZ = 440.0
        const val TONE_AMPLITUDE = 8_192.0
    }
}
