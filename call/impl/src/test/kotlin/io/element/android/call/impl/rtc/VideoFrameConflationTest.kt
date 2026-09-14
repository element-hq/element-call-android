/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrixrtc.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrixrtc.api.MatrixRtcVideoFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * That conflating frames frees the ones it throws away.
 *
 * The reason this is tested rather than trusted: a leak here is invisible. Nothing fails, no test
 * goes red, the picture is perfect - the device simply runs out of memory after a few minutes of a
 * renderer being slightly behind, which is the ordinary state of a renderer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoFrameConflationTest {
    @Test
    fun `frames that are delivered are released once the collector is done`() = runTest {
        val freed = AtomicInteger(0)
        val frames = List(3) { aFrame { freed.incrementAndGet() } }

        val collected = flow { frames.forEach { emit(it) } }
            .conflateReleasingDropped()
            .toList()

        assertThat(collected).isNotEmpty()
        assertThat(freed.get()).isEqualTo(frames.size)
    }

    /**
     * The case the whole operator exists for: a producer faster than its consumer.
     *
     * Every frame must be accounted for - the ones drawn and the ones dropped alike - even though
     * only some reach the collector.
     */
    @Test
    fun `frames dropped by a slow collector are released rather than leaked`() = runTest {
        val freed = AtomicInteger(0)
        val count = 100
        val frames = List(count) { aFrame { freed.incrementAndGet() } }

        // No yielding: the producer runs to completion before the collector gets a turn, which is
        // what overflows a one-deep buffer. Yielding between frames let the collector keep up and
        // nothing was dropped at all - the first version of this test passed without testing
        // anything.
        val collected = flow { frames.forEach { emit(it) } }
            .conflateReleasingDropped()
            .toList()

        // Conflation did its job, so almost none of them reached the collector...
        assertThat(collected.size).isLessThan(count)
        // ...and every single one was still freed.
        assertThat(freed.get()).isEqualTo(count)
    }

    /**
     * A tile leaving the screen mid-stream: the collector stops, and whatever was in flight has to be
     * freed rather than stranded in a buffer nobody will ever read.
     */
    @Test
    fun `abandoning the collector releases what was still buffered`() = runTest {
        val freed = AtomicInteger(0)
        val emitted = AtomicInteger(0)

        flow {
            repeat(20) {
                emit(aFrame { freed.incrementAndGet() })
                emitted.incrementAndGet()
                // Hands the collector a turn, so it can walk away partway through rather than after
                // the producer has already finished.
                yield()
            }
        }
            .conflateReleasingDropped()
            // Walks away after the first frame, exactly as a disposed tile does.
            .take(1)
            .toList()

        // Everything the producer actually got as far as emitting was freed. Not the whole twenty:
        // cancelling stops the producer, so the rest are never created, which is a stronger outcome
        // than freeing them.
        assertThat(emitted.get()).isAtLeast(1)
        assertThat(freed.get()).isEqualTo(emitted.get())
    }

    /** A frame a subscriber retained outlives the operator letting go of it. */
    @Test
    fun `a frame retained by the collector is not freed underneath it`() = runTest {
        val freed = AtomicInteger(0)
        val frame = aFrame { freed.incrementAndGet() }
        var held: MatrixRtcVideoFrame? = null

        flow { emit(frame) }
            .conflateReleasingDropped()
            .collect {
                it.retain()
                held = it
            }

        assertThat(freed.get()).isEqualTo(0)
        held?.release()
        assertThat(freed.get()).isEqualTo(1)
    }

    /**
     * A frame is still alive while the collector has it, and only then.
     *
     * The bug this pins down shipped and reached a device: with a buffering operator downstream -
     * `flowOn` puts a 64-deep channel in the way - `emit` returns as soon as the frame is *queued*,
     * so the release fired while the frame was still waiting to be drawn. The core kept decoding at
     * 30fps, every frame was freed before the renderer saw it, and about one in a hundred made it to
     * the screen. Nothing failed; the picture just went slow.
     *
     * Asserting inside the collector is the only way to catch it, because from outside a
     * released-too-early frame and a correctly released one look identical once collection ends.
     */
    @Test
    fun `a frame is not released before the collector has seen it`() = runTest {
        val freed = AtomicInteger(0)
        var wasAliveWhenCollected: Boolean? = null

        flow { emit(aFrame { freed.incrementAndGet() }) }
            .conflateReleasingDropped()
            .collect { frame ->
                // Retaining is what a renderer does; it fails on a frame that has already been freed.
                wasAliveWhenCollected = frame.retain()
                if (wasAliveWhenCollected == true) frame.release()
            }

        assertThat(wasAliveWhenCollected).isTrue()
        assertThat(freed.get()).isEqualTo(1)
    }

    private fun aFrame(onRelease: () -> Unit) = MatrixRtcVideoFrame(
        width = 2,
        height = 2,
        rotationDegrees = 0,
        timestampUs = 0,
        dataY = ByteBuffer.allocateDirect(4),
        strideY = 2,
        dataU = ByteBuffer.allocateDirect(1),
        strideU = 1,
        dataV = ByteBuffer.allocateDirect(1),
        strideV = 1,
        onRelease = onRelease,
    )
}
