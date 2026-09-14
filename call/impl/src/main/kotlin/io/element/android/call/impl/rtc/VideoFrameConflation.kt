/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrixrtc.impl

import io.element.android.libraries.matrixrtc.api.MatrixRtcVideoFrame
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * Keep only the newest frame, releasing the ones that fall out.
 *
 * A renderer that has fallen behind must not become backpressure on the decoder, so old frames are
 * dropped - and since a frame now owns native memory, a dropped frame that nobody releases is a leak
 * of most of a megabyte. Dropping is the *normal* case for a renderer under load, so the naive
 * version would leak hardest under exactly the conditions that cause it.
 *
 * `buffer(1, DROP_OLDEST)` does the conflating and cannot do the releasing: a dropped element is
 * never collected, so nothing downstream ever sees it. [Channel] is the only buffering primitive with
 * an undelivered-element hook, which is the sole reason this is written out by hand rather than being
 * one operator.
 *
 * Ownership, which is the part worth being precise about:
 * - **Buffered**: the channel holds the reference, and either delivers the frame or releases it on
 *   overflow.
 * - **Delivered**: this releases the producer's reference once `emit` returns. A subscriber that
 *   needs the frame for longer than its own collector - a renderer handing it to a GL thread - must
 *   have retained it by then.
 * - **Abandoned**: cancelling the collector cancels the channel, which releases whatever was still
 *   buffered. A tile leaving the screen mid-frame is routine, not exceptional.
 *
 * Top-level and internal rather than private to the call, so the leak behaviour can be tested. It is
 * the one property here whose failure is invisible until a device runs out of memory.
 */
internal fun Flow<MatrixRtcVideoFrame>.conflateReleasingDropped(): Flow<MatrixRtcVideoFrame> = flow {
    coroutineScope {
        val channel = Channel<MatrixRtcVideoFrame>(
            capacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = { it.release() },
        )
        launch {
            try {
                // send never suspends with DROP_OLDEST, so the decoder is never held up by a slow
                // renderer - which is the whole point of conflating.
                collect { frame -> channel.send(frame) }
                channel.close()
            } catch (throwable: Throwable) {
                channel.close(throwable)
            }
        }
        try {
            for (frame in channel) {
                try {
                    emit(frame)
                } finally {
                    frame.release()
                }
            }
        } finally {
            channel.cancel()
        }
    }
}
