/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc.media

import android.os.Process
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * A thread of its own for one audio loop, at the priority the platform gives its own audio threads.
 *
 * The capture and playback loops used to run on the call scope's `Dispatchers.IO`, which in
 * kotlinx.coroutines is a view over the same pool as `Dispatchers.Default`. That put them among
 * ordinary `nice = 0` workers, queued behind video decode, encode and GC - while the `AudioTrack`
 * and `AudioRecord` threads they feed sit at `nice = -16`, and `MediaCodec` at `nice = -10`. The
 * platform drains a track faster than an unprioritised writer refills it, and the difference comes
 * out as a crackle.
 *
 * Measured on a Pixel 5 in a five-party call, from the frame counter in [AudioLevelMeter] - 500
 * frames is 5.00 s of audio by construction, so the wall clock between two log lines is the drift:
 *
 * | | camera off | camera on |
 * | :-- | --: | --: |
 * | 500 frames of playback | 4.98-5.02 s | 5.45-5.97 s |
 * | `pollReceiveStats` 1 s period | ~1.08 s | 1.39-1.53 s |
 *
 * Roughly 15% of every track's output was under-run samples, with RTP delivery clean throughout
 * (`0 lost, 0% invented`) - so this was never a network fault. Enabling the local camera is what
 * tipped the pool over, and turning it off is what made it stop.
 *
 * One thread per loop rather than one shared by all of them: a write blocks for as long as the
 * track needs, and one member's blocking write must not delay another member's.
 *
 * @param name shows up in `top -H` and in a tombstone, so it names the loop and not just the class.
 */
internal fun audioDispatcher(name: String): ExecutorCoroutineDispatcher =
    Executors.newSingleThreadExecutor { runnable ->
        Thread(
            {
                // Set from the thread itself: setThreadPriority defaults to the calling thread, and
                // there is no way to ask for a priority at construction. THREAD_PRIORITY_URGENT_AUDIO
                // is what libwebrtc's own WebRtcAudioTrack/WebRtcAudioRecord use - they ship inside
                // the AAR, unused, because the FFI hands us PCM and we open the devices ourselves.
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                runnable.run()
            },
            name,
        )
    }.asCoroutineDispatcher()
