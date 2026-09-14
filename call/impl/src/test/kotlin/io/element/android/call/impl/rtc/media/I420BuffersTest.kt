/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrixrtc.impl.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer

class I420BuffersTest {
    /**
     * The failure this guards is a picture that shears further sideways down the frame: padding
     * copied as if it were image data pushes every row along by the difference.
     */
    @Test
    fun `padding between rows is not copied as picture`() {
        // Three rows of two visible bytes, each row padded out to four. The 99s are the padding, and
        // are the bytes that must not appear in the result.
        val plane = planeOf(
            byteArrayOf(1, 2, 99, 99),
            byteArrayOf(3, 4, 99, 99),
            byteArrayOf(5, 6, 99, 99),
        )

        val packed = I420Buffers.packPlane(plane, stride = 4, rowWidth = 2, rowCount = 3)

        assertThat(packed.toList()).containsExactly(1.toByte(), 2.toByte(), 3.toByte(), 4.toByte(), 5.toByte(), 6.toByte()).inOrder()
    }

    /** With no padding the copy is the identity, which is the ordinary case and must stay cheap and right. */
    @Test
    fun `a plane with no padding round trips unchanged`() {
        val plane = planeOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))

        val packed = I420Buffers.packPlane(plane, stride = 3, rowWidth = 3, rowCount = 2)

        assertThat(packed.toList()).containsExactly(1.toByte(), 2.toByte(), 3.toByte(), 4.toByte(), 5.toByte(), 6.toByte()).inOrder()
    }

    /**
     * A capturer is entitled to allocate exactly what the plane needs, which is one row short of
     * `stride * rowCount` - so requiring the full rectangle would reject valid frames.
     */
    @Test
    fun `a buffer with no padding after the last row is accepted`() {
        val plane = ByteBuffer.allocate(4 + 4 + 2).apply {
            put(byteArrayOf(1, 2, 99, 99))
            put(byteArrayOf(3, 4, 99, 99))
            put(byteArrayOf(5, 6))
            rewind()
        }

        val packed = I420Buffers.packPlane(plane, stride = 4, rowWidth = 2, rowCount = 3)

        assertThat(packed.toList()).containsExactly(1.toByte(), 2.toByte(), 3.toByte(), 4.toByte(), 5.toByte(), 6.toByte()).inOrder()
    }

    /**
     * The alternative to throwing is filling the bottom of the picture with whatever the buffer
     * happened to hold, which looks like a decode fault rather than a geometry mistake.
     */
    @Test
    fun `a buffer too small for the plane described is refused`() {
        val plane = ByteBuffer.allocate(5)

        runCatching { I420Buffers.packPlane(plane, stride = 4, rowWidth = 4, rowCount = 3) }
            .onSuccess { error("expected a plane that does not fit to be refused") }
            .onFailure { assertThat(it).isInstanceOf(IllegalArgumentException::class.java) }
    }

    /**
     * The caller hands us the same buffer again for the next frame, so reading must not consume it.
     */
    @Test
    fun `packing leaves the source position alone`() {
        val plane = planeOf(byteArrayOf(1, 2), byteArrayOf(3, 4))

        I420Buffers.packPlane(plane, stride = 2, rowWidth = 2, rowCount = 2)

        assertThat(plane.position()).isEqualTo(0)
    }

    /**
     * Chroma planes are half resolution rounded *up*. Rounding down drops the last column and
     * shifts every row after the first, so an odd-width frame would shear - and cameras do report
     * odd dimensions.
     */
    @Test
    fun `odd dimensions round the chroma planes up`() {
        assertThat(I420Buffers.chromaWidth(641)).isEqualTo(321)
        assertThat(I420Buffers.chromaHeight(481)).isEqualTo(241)
    }

    @Test
    fun `even dimensions halve the chroma planes exactly`() {
        assertThat(I420Buffers.chromaWidth(640)).isEqualTo(320)
        assertThat(I420Buffers.chromaHeight(480)).isEqualTo(240)
    }

    /**
     * A renderer can only wrap a direct buffer without copying, so this being direct is a contract
     * and not a detail - `JavaI420Buffer.wrap` rejects anything else.
     */
    @Test
    fun `the buffer handed to a renderer is direct and ready to read`() {
        val direct = I420Buffers.toDirectBuffer(byteArrayOf(1, 2, 3))

        assertThat(direct.isDirect).isTrue()
        assertThat(direct.position()).isEqualTo(0)
        assertThat(direct.remaining()).isEqualTo(3)
        assertThat(direct.get(2)).isEqualTo(3.toByte())
    }

    // The receive side used to have its own packer, and its own three tests here. Both are gone:
    // frames now come out of the core as native pointers wrapped in place, with the source's stride
    // passed straight through to the renderer, so there is no repacking left to get wrong.

    /** Rows laid out back to back, as a capturer hands them over. */
    private fun planeOf(vararg rows: ByteArray): ByteBuffer =
        ByteBuffer.allocate(rows.sumOf { it.size }).apply {
            rows.forEach { put(it) }
            rewind()
        }
}
