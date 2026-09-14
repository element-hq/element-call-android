/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrixrtc.impl.media

import java.nio.ByteBuffer

/**
 * Moving I420 planes between the shapes the two sides of the FFI want.
 *
 * The only real work here is the plane geometry, and it is the kind that is wrong silently. A plane
 * has a *stride* - the distance from the start of one row to the start of the next - which is
 * allowed to exceed the row's width, because a capturer may align rows for its own convenience.
 * Copying `stride * rows` bytes in one go therefore copies the padding as picture, which shows up as
 * a frame that shears progressively further sideways down the image. Every copy here goes row by
 * row and repacks tightly, so what comes out has `stride == rowWidth` and no padding at all.
 *
 * The chroma planes are half resolution, rounded **up**: a 641-pixel-wide frame has 321 chroma
 * samples per row, not 320. Rounding down loses the last column and shifts every row after the
 * first, which again reads as shear rather than as a missing column.
 */
internal object I420Buffers {
    /** Chroma samples per row for a [width]-pixel plane. Rounded up: see the class comment. */
    fun chromaWidth(width: Int): Int = (width + 1) / 2

    /** Chroma rows for a [height]-pixel plane. Rounded up: see the class comment. */
    fun chromaHeight(height: Int): Int = (height + 1) / 2

    /**
     * Copy [rowCount] rows of [rowWidth] bytes out of [source], skipping [stride] bytes per row, into
     * a tightly packed array.
     *
     * Reads through a duplicate, so the caller's position and limit are left alone - the same buffer
     * is handed to us again for the next frame.
     *
     * @throws IllegalArgumentException if [source] is too small to hold the plane described. Loud on
     * purpose: the alternative is a short read filling the bottom of the picture with whatever the
     * buffer happened to contain.
     */
    fun packPlane(source: ByteBuffer, stride: Int, rowWidth: Int, rowCount: Int): ByteArray {
        // The last row needs only its own width, not a full stride - a capturer is entitled to
        // allocate exactly that and no more.
        val required = if (rowCount == 0) 0 else stride * (rowCount - 1) + rowWidth
        require(source.capacity() >= required) {
            "I420 plane needs $required bytes (${rowCount}x$rowWidth at stride $stride), buffer holds ${source.capacity()}"
        }
        val packed = ByteArray(rowWidth * rowCount)
        val reader = source.duplicate()
        for (row in 0 until rowCount) {
            reader.position(row * stride)
            reader.get(packed, row * rowWidth, rowWidth)
        }
        return packed
    }

    /**
     * Wrap a packed plane in a direct buffer, which is the only kind a renderer can hand to GL
     * without copying it again.
     *
     * Positioned at zero and ready to read.
     */
    fun toDirectBuffer(packed: ByteArray): ByteBuffer = ByteBuffer
        .allocateDirect(packed.size)
        .put(packed)
        .apply { rewind() }
}
