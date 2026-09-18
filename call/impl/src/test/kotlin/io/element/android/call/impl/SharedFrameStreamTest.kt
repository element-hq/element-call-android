/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * The reference accounting when one video stream feeds several tiles.
 *
 * Every property here has already been got wrong on a real device, in a way that produced no error at
 * all: frames were freed while the renderer was still being dispatched to, and the only symptom was
 * the picture going black.
 *
 * **The upstream here releases each frame after emitting it**, because that is what the real one
 * does: `conflateReleasingDropped` hands a frame over for the duration of the call and then lets go
 * of its own reference. An earlier version of this file used a bare `MutableSharedFlow`, which
 * releases nothing, and so quietly tested a world where `SharedFrameStream` owned the frame. It
 * passed while the real chain double-freed every frame it carried.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedFrameStreamTest {
    /**
     * The property the whole class exists for: a frame is still alive when a collector has it.
     *
     * Checked *inside* the collector, because that is the only place the failure is visible - from
     * outside, a frame freed too early and one freed correctly look identical afterwards.
     */
    @Test
    fun `a frame is alive while a collector holds it`() = runTest {
        val freed = AtomicInteger(0)
        val upstream = upstreamOf(1) { freed.incrementAndGet() }
        val stream = SharedFrameStream(upstream.frames, backgroundScope, lingerMillis = 0)
        val aliveWhenSeen = mutableListOf<Boolean>()

        backgroundScope.launch {
            stream.frames.collect { frame ->
                // Retaining is exactly what the renderer does, and it fails on a freed frame.
                aliveWhenSeen += frame.retain()
                frame.release()
            }
        }
        runCurrent()
        upstream.emitAll()
        runCurrent()

        assertThat(aliveWhenSeen).containsExactly(true)
        assertThat(freed.get()).isEqualTo(1)
    }

    /** Two tiles on one member each get the frame, and it survives until both are done with it. */
    @Test
    fun `a frame delivered to two collectors is freed once, after both`() = runTest {
        val freed = AtomicInteger(0)
        val upstream = upstreamOf(1) { freed.incrementAndGet() }
        val stream = SharedFrameStream(upstream.frames, backgroundScope, lingerMillis = 0)
        val aliveWhenSeen = mutableListOf<Boolean>()

        repeat(2) {
            backgroundScope.launch {
                stream.frames.collect { frame ->
                    aliveWhenSeen += frame.retain()
                    frame.release()
                }
            }
        }
        runCurrent()
        upstream.emitAll()
        runCurrent()

        // Both saw it alive, and it was freed exactly once rather than twice.
        assertThat(aliveWhenSeen).containsExactly(true, true)
        assertThat(freed.get()).isEqualTo(1)
    }

    /**
     * With nobody watching, a frame is freed rather than queued.
     *
     * The upstream's own release is what does it, and that is the point: this class taking a second
     * reference here would leak, and releasing one it never took would double free.
     */
    @Test
    fun `frames produced with no collector are still released`() = runTest {
        val freed = AtomicInteger(0)
        // A long linger, so the upstream is still running with nobody left to receive - the window
        // that exists between the last tile going away and the stream being torn down.
        val upstream = upstreamOf(1) { freed.incrementAndGet() }
        val stream = SharedFrameStream(upstream.frames, backgroundScope, lingerMillis = 10_000)

        val collector = backgroundScope.launch { stream.frames.collect { } }
        runCurrent()
        collector.cancel()
        runCurrent()

        upstream.emitAll()
        runCurrent()

        assertThat(freed.get()).isEqualTo(1)
    }

    /** A collector that walks away mid-stream leaves nothing behind it. */
    @Test
    fun `abandoning a collector releases what it still held`() = runTest {
        val freed = AtomicInteger(0)
        val count = 10
        val upstream = upstreamOf(count) { freed.incrementAndGet() }
        val stream = SharedFrameStream(upstream.frames, backgroundScope, lingerMillis = 0)

        // Never drains: every frame ends up queued or dropped, never collected.
        val collector = backgroundScope.launch {
            stream.frames.collect { awaitCancellation() }
        }
        runCurrent()
        upstream.emitAll()
        runCurrent()

        collector.cancel()
        runCurrent()

        assertThat(freed.get()).isEqualTo(count)
    }

    /**
     * A producer shaped like the real one: it emits a frame, waits for the emit to return, and then
     * releases its own single reference.
     */
    private fun upstreamOf(count: Int, onRelease: () -> Unit): TestUpstream {
        val gate = Channel<Unit>(capacity = count)
        val frames = flow {
            repeat(count) {
                gate.receive()
                val frame = aFrame(onRelease)
                try {
                    emit(frame)
                } finally {
                    frame.release()
                }
            }
        }
        return TestUpstream(frames) { repeat(count) { gate.trySend(Unit) } }
    }

    private data class TestUpstream(val frames: Flow<MatrixRtcVideoFrame>, val emitAll: () -> Unit)

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
