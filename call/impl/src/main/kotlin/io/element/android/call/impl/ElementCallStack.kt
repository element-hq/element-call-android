/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl

import android.content.Context
import io.element.android.call.api.ElementCallController
import io.element.android.call.api.ElementCallDispatchers
import io.element.android.call.api.ElementCallLifecycleListener
import io.element.android.call.api.ElementCallOptions
import io.element.android.call.api.ElementCallRoomContextProvider
import io.element.android.call.api.NoOpElementCallLifecycleListener
import io.element.android.call.api.NoOpElementCallRoomContextProvider
import io.element.android.call.api.audio.AudioFocus
import io.element.android.call.api.audio.CallAudioDeviceController
import io.element.android.call.api.matrix.ElementCallMatrixTransport
import io.element.android.call.api.rtc.MatrixRtcService
import io.element.android.call.impl.audio.DefaultAudioFocus
import io.element.android.call.impl.audio.DefaultCallAudioDeviceController
import io.element.android.call.impl.rtc.MatrixRtcFfi
import io.element.android.call.impl.rtc.RustMatrixRtcService
import kotlinx.coroutines.CoroutineScope

/**
 * Everything the native call needs for one Matrix session, built by hand: no dependency-injection
 * framework, so the stack is constructible in a test, in the sample app and in any host alike.
 *
 * ```
 * val stack = ElementCallStack.Builder(context, transport)
 *     .roomContext(roomContextProvider)      // host only
 *     .lifecycleListener(listener)           // host only
 *     .options(ElementCallOptions())
 *     .build(scope = sessionScope)
 * stack.start()                              // brings the RTC core up: with the session, not the call
 * stack.controller                           // what the UI and the host drive
 * ```
 *
 * Build one per Matrix session and keep it for the session's life. [start] is what makes an incoming
 * media key land: the core's to-device subscription exists from then on, between calls included.
 */
class ElementCallStack private constructor(
    val controller: ElementCallController,
    val options: ElementCallOptions,
    private val rtcService: MatrixRtcService,
) {
    /**
     * Bring the RTC core up. Call it as soon as the session exists, not when a call starts: a key
     * sent while nothing is subscribed is gone, and the symptom is a member stuck at `MISSING_KEY`
     * for the rest of the call. Idempotent.
     */
    suspend fun start() {
        rtcService.start()
    }

    /** Forget the stack. The session scope it was built with tears the rest down. */
    fun close() {
        ElementCallStackRegistry.unregister(this)
    }

    class Builder(
        context: Context,
        private val transport: ElementCallMatrixTransport,
    ) {
        private val context: Context = context.applicationContext
        private var roomContextProvider: ElementCallRoomContextProvider = NoOpElementCallRoomContextProvider
        private var lifecycleListener: ElementCallLifecycleListener = NoOpElementCallLifecycleListener
        private var audioDeviceController: CallAudioDeviceController? = null
        private var audioFocus: AudioFocus? = null
        private var options: ElementCallOptions = ElementCallOptions()
        private var dispatchers: ElementCallDispatchers = ElementCallDispatchers.Default

        /** Room names, direct flags and member profiles. Defaults to none: tiles show user ids. */
        fun roomContext(provider: ElementCallRoomContextProvider) = apply { roomContextProvider = provider }

        /** Call started, joined and ended, and whether the app is in the foreground. Defaults to a no-op. */
        fun lifecycleListener(listener: ElementCallLifecycleListener) = apply { lifecycleListener = listener }

        /** Audio routing. Defaults to [DefaultCallAudioDeviceController] over `AudioManager`. */
        fun audioDeviceController(controller: CallAudioDeviceController) = apply { audioDeviceController = controller }

        /** Audio focus. Defaults to [DefaultAudioFocus]; a host coordinating focus with its own players supplies its own. */
        fun audioFocus(focus: AudioFocus) = apply { audioFocus = focus }

        fun options(options: ElementCallOptions) = apply { this.options = options }

        fun dispatchers(dispatchers: ElementCallDispatchers) = apply { this.dispatchers = dispatchers }

        /**
         * @param scope lives as long as the Matrix session. The core, its feeds and the running call
         * hang off it, so cancelling it is how a logout tears everything down.
         */
        fun build(scope: CoroutineScope): ElementCallStack {
            // Recorded before the library loads; applied on the first native call.
            options.logging?.let { MatrixRtcFfi.setLoggingConfiguration(it) }
            val rtcService = RustMatrixRtcService(
                transport = transport,
                dispatchers = dispatchers,
                context = context,
                sessionCoroutineScope = scope,
            )
            val controller = DefaultElementCallController(
                scope = scope,
                platform = DefaultElementCallPlatform(context),
                rtcService = rtcService,
                audioDeviceController = audioDeviceController ?: DefaultCallAudioDeviceController(context),
                audioFocus = audioFocus ?: DefaultAudioFocus(context),
                lifecycleListener = lifecycleListener,
                roomContextProvider = roomContextProvider,
                options = options,
            )
            return ElementCallStack(controller, options, rtcService).also { ElementCallStackRegistry.register(it) }
        }
    }
}
