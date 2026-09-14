/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl

import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fans one video stream out to several collectors, counting references as it goes.
 *
 * Two requirements meet here, and the ordinary tools satisfy one each.
 *
 * **One stream, however many tiles draw it.** `MatrixRtcCall.videoFrames` is cold and opens a
 * `videoStream` handle per collector; two handles on one track crashed the core inside
 * `VideoSinkWrapper::on_frame`. That is what `shareIn` was doing here.
 *
 * **A frame must outlive every consumer that is still using it.** `shareIn` cannot do this, and the
 * reason is worth stating precisely because it cost two attempts to see: `SharedFlow.emit` is an
 * *asynchronous* handoff. It resumes once a subscriber has been woken, not once that subscriber's
 * collector has run - so a producer that releases after `emit` frees the frame while the renderer is
 * still being dispatched to. On device that was about a third of frames arriving already released,
 * and the rest of the picture black.
 *
 * So the fan-out is explicit: a frame is **retained once per subscriber before being offered**, and
 * each subscriber releases its own reference when its collector returns. Every path is accounted for:
 *
 * - **No subscribers** - the frame is released immediately and never queued. This is the window
 *   `shareIn` silently dropped frames in, which with owned memory was a leak rather than a saving.
 * - **A subscriber that has fallen behind** - its own one-deep channel drops the older frame and
 *   releases it, so a slow renderer costs frames rather than memory, and never blocks the decoder or
 *   the other subscribers.
 * - **A subscriber that goes away** - cancelling its channel releases whatever it still held.
 *
 * Collectors are called inline from their own channel loop, with no dispatcher change in between,
 * which is what makes "release after emit" a fact rather than a hope.
 *
 * @param linger how long the upstream stays open after the last collector leaves, so a tile that is
 * destroyed and immediately rebuilt does not close and reopen the stream underneath the core.
 */
internal class SharedFrameStream(
    private val upstream: Flow<MatrixRtcVideoFrame>,
    private val scope: CoroutineScope,
    private val lingerMillis: Long,
) {
    private val subscribers = CopyOnWriteArrayList<Channel<MatrixRtcVideoFrame>>()

    /** The upstream collection, alive only while somebody is watching (plus [lingerMillis]). */
    private var pump: Job? = null

    /** Cancels the pump after the linger, unless somebody subscribes again first. */
    private var stopper: Job? = null

    val frames: Flow<MatrixRtcVideoFrame> = flow {
        val channel = Channel<MatrixRtcVideoFrame>(
            capacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = { it.release() },
        )
        subscribe(channel)
        try {
            for (frame in channel) {
                try {
                    emit(frame)
                } finally {
                    // This subscriber's own reference, taken on its behalf before the frame was
                    // offered. The renderer will have retained its own by now if it needs one.
                    frame.release()
                }
            }
        } finally {
            unsubscribe(channel)
            // Frees anything still queued for a collector that has walked away.
            channel.cancel()
        }
    }

    @Synchronized
    private fun subscribe(channel: Channel<MatrixRtcVideoFrame>) {
        subscribers += channel
        stopper?.cancel()
        stopper = null
        if (pump == null) {
            pump = scope.launch {
                upstream.collect { frame -> dispatch(frame) }
            }
        }
    }

    @Synchronized
    private fun unsubscribe(channel: Channel<MatrixRtcVideoFrame>) {
        subscribers -= channel
        if (subscribers.isEmpty() && stopper == null) {
            stopper = scope.launch {
                delay(lingerMillis)
                stopPump()
            }
        }
    }

    @Synchronized
    private fun stopPump() {
        if (subscribers.isNotEmpty()) return
        pump?.cancel()
        pump = null
        stopper = null
    }

    /**
     * Hand one frame to everybody currently watching, taking a reference for each.
     *
     * **This does not own the frame and must never release the upstream's reference.** The upstream
     * hands the frame over for the duration of this call and releases it itself once this returns -
     * that is the contract every layer here follows, and having this release it too was a double free
     * of one reference. On device it freed the frame the instant it was dispatched, so every renderer
     * got a dead frame and the picture went black. Only the per-subscriber references taken here are
     * this class's to give back, and only when a send fails.
     *
     * The retain happens *before* the offer, so a frame can never reach a subscriber already freed.
     */
    private fun dispatch(frame: MatrixRtcVideoFrame) {
        for (channel in subscribers) {
            if (frame.retain()) {
                // trySend rather than send: with DROP_OLDEST it always succeeds, and a suspending
                // send here would let one slow subscriber hold up every other one.
                if (channel.trySend(frame).isFailure) frame.release()
            }
        }
    }
}
