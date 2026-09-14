/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The reference counting a decoded frame's planes now depend on.
 *
 * Worth testing directly and in detail, because every way of getting it wrong is a native fault
 * rather than a wrong picture: free too early and the GL thread reads freed memory, free twice and
 * the allocator faults, free never and a megabyte leaks per frame at thirty frames a second.
 */
class MatrixRtcVideoFrameTest {
    @Test
    fun `a frame arrives with one reference and frees when it is given up`() {
        val freed = AtomicInteger(0)
        val frame = aFrame { freed.incrementAndGet() }

        assertThat(freed.get()).isEqualTo(0)
        frame.release()

        assertThat(freed.get()).isEqualTo(1)
    }

    /** The renderer's case: it holds the frame past the call that handed it over. */
    @Test
    fun `a retained frame survives its producer letting go`() {
        val freed = AtomicInteger(0)
        val frame = aFrame { freed.incrementAndGet() }

        assertThat(frame.retain()).isTrue()
        // The producer is done with it, but the renderer is not.
        frame.release()
        assertThat(freed.get()).isEqualTo(0)

        frame.release()
        assertThat(freed.get()).isEqualTo(1)
    }

    /**
     * Releasing more often than retaining must not double free.
     *
     * Reachable in practice: a renderer, a dropped buffer's undelivered-element hook and teardown can
     * all reach the same frame, and proving exactly one of them wins every race is harder than making
     * the extra calls harmless.
     */
    @Test
    fun `releasing past zero is ignored rather than freeing twice`() {
        val freed = AtomicInteger(0)
        val frame = aFrame { freed.incrementAndGet() }

        repeat(5) { frame.release() }

        assertThat(freed.get()).isEqualTo(1)
    }

    /** Retaining a frame that is already gone reports failure instead of resurrecting freed memory. */
    @Test
    fun `a freed frame refuses to be retained`() {
        val frame = aFrame {}
        frame.release()

        assertThat(frame.retain()).isFalse()
    }

    /**
     * The counting has to hold across threads, because it genuinely spans them: frames are produced
     * on an IO dispatcher, drawn on libwebrtc's GL thread and dropped on whichever thread the buffer
     * overflowed on. The contract explicitly allows release from any thread.
     */
    @Test
    fun `concurrent retain and release still frees exactly once`() {
        repeat(50) {
            val freed = AtomicInteger(0)
            val frame = aFrame { freed.incrementAndGet() }
            val threadCount = 8
            val start = CountDownLatch(1)
            val done = CountDownLatch(threadCount)

            repeat(threadCount) {
                thread {
                    start.await()
                    if (frame.retain()) frame.release()
                    done.countDown()
                }
            }
            start.countDown()
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue()

            // Every retain was paired, so the producer's own reference is the only one left.
            assertThat(freed.get()).isEqualTo(0)
            frame.release()
            assertThat(freed.get()).isEqualTo(1)
        }
    }

    /** A locally captured frame owns nothing native, so releasing it is pure bookkeeping. */
    @Test
    fun `a frame with no release callback can still be released`() {
        val frame = MatrixRtcVideoFrame(
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
        )

        frame.release()
        assertThat(frame.retain()).isFalse()
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
