/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * One video frame, in I420: three planes of 8-bit samples, full-resolution luma and
 * quarter-resolution chroma.
 *
 * **A frame is a resource, not a value.** Its planes are reference counted: it arrives with one
 * reference, anyone who needs it past the call that handed it over must [retain] it, and every holder
 * must [release] exactly once. A decoded frame's planes are native memory owned by the core, so
 * reading one after the last reference goes is a use-after-free rather than a wrong picture - the
 * whole reason this type used to copy instead.
 *
 * Deliberately not a `data class`. A [ByteBuffer]'s `equals` compares remaining content, so a
 * generated `equals` would walk a megabyte on every comparison and a generated `hashCode` would do
 * the same - both on frames arriving thirty times a second.
 *
 * The buffers are **direct**, which is not an implementation detail the caller may ignore: it is what
 * lets a renderer wrap them without copying. Treat them as read-only, and note that the strides are
 * the source's own - a plane's rows are `stride` bytes apart, which for a decoded frame is usually
 * *more* than `width`. A reader that assumes tightly packed rows gets a sheared picture.
 *
 * Holding several at once is safe: each owns its own memory and nothing is recycled between them, so
 * a renderer with one in flight while the next arrives is fine. Holding costs memory and at worst a
 * dropped frame, never a corrupted one. Reads may happen on any thread, unsynchronised, and release
 * may happen on any thread - the single rule is that every read happens before the release.
 *
 * @param rotationDegrees how far the frame must be rotated clockwise to be displayed upright. The
 * capture device reports this rather than rotating pixels, so a renderer that ignores it shows a
 * sideways picture on a phone held upright.
 * @param timestampUs the capture timestamp in microseconds, on the sender's clock. Only useful for
 * ordering and for spotting a stalled stream; it is not comparable to anything local.
 * @param onRelease frees whatever backs the planes. Called at most once, by [release]. Absent for a
 * frame whose planes are ordinary heap buffers - locally captured ones - where releasing is nothing
 * but bookkeeping and the garbage collector does the rest.
 */
class MatrixRtcVideoFrame(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val timestampUs: Long,
    val dataY: ByteBuffer,
    val strideY: Int,
    val dataU: ByteBuffer,
    val strideU: Int,
    val dataV: ByteBuffer,
    val strideV: Int,
    private val onRelease: (() -> Unit)? = null,
) {
    /**
     * How many holders still need the planes. Starts at one: whoever built the frame.
     *
     * Reference counted rather than released once, because a frame legitimately has more than one
     * holder. The stream that produced it holds one while it is in flight; a renderer holds another
     * for as long as the GL thread is drawing it, which outlives the call that handed it over; and a
     * frame shared to two tiles has one holder each. Deciding by argument which of those "really"
     * owns it is exactly the reasoning that has cost this area three crashes, so it is counted
     * instead.
     */
    private val holders = AtomicInteger(1)

    /**
     * Claim a reference. Must be paired with exactly one [release].
     *
     * Call it *before* the current holder's [release] can run - retaining a frame that has already
     * reached zero is a use-after-free, and it returns false to say so rather than pretending.
     */
    fun retain(): Boolean {
        while (true) {
            val current = holders.get()
            if (current <= 0) return false
            if (holders.compareAndSet(current, current + 1)) return true
        }
    }

    /**
     * Give up a reference, freeing the planes when the last one goes. Safe from any thread.
     *
     * Extra calls past zero are ignored rather than double-freeing: this is reached from a renderer,
     * from a dropped buffer's undelivered-element hook and from teardown, and a harmless second call
     * is much better than a native double free.
     */
    fun release() {
        if (holders.get() <= 0) return
        if (holders.decrementAndGet() == 0) {
            onRelease?.invoke()
        }
    }

    override fun toString(): String = "MatrixRtcVideoFrame(${width}x$height, rotation $rotationDegrees, at $timestampUs us)"
}
