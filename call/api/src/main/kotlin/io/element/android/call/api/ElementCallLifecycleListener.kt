/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * What the host wants to know about the call's life, and the one thing the call wants to know about
 * the host.
 *
 * The three events are what Element X's trackers need: to stop a ringtone and cancel the missed-call
 * timer when the call is joined, to keep its WebView call and this one from both claiming the call,
 * and to know whether the app is "in a call" for sync purposes. None of that is the library's
 * business, so it is reported here and the host does what it likes with it.
 */
interface ElementCallLifecycleListener {
    /**
     * Whether the app is in the foreground. Read for the proximity rule: the screen is only blanked
     * when the phone might be at someone's ear, and blanking another app's screen is never ours to do.
     */
    val isAppInForeground: Flow<Boolean>

    /**
     * A call was requested, before anything is joined. Reported this early so a host can hide its own
     * "join" affordances while the join is in flight rather than after it.
     */
    fun onCallStarted(callData: ElementCallData)

    /** The call is joined and media is connected: whatever was ringing for it has been answered. */
    fun onCallJoined(callData: ElementCallData)

    /**
     * The call is over, whichever side ended it. [callData] is what [onCallStarted] received, or null
     * in the one case a call is torn down before it had a snapshot.
     */
    fun onCallEnded(callData: ElementCallData?)
}

/** The default: a host with nothing to track, always in the foreground. */
object NoOpElementCallLifecycleListener : ElementCallLifecycleListener {
    override val isAppInForeground: Flow<Boolean> = flowOf(true)

    override fun onCallStarted(callData: ElementCallData) = Unit

    override fun onCallJoined(callData: ElementCallData) = Unit

    override fun onCallEnded(callData: ElementCallData?) = Unit
}
