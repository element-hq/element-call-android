/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.audio

import kotlinx.coroutines.flow.StateFlow

/**
 * Where call audio goes, and what else it could go to.
 *
 * Routing choices only stick while something holds the device in communication mode, so [start] and
 * [stop] bracket the whole thing and nothing else here means anything outside that bracket.
 *
 * The list is live: a Bluetooth headset switched on mid-call appears in [devices] without anyone
 * asking, which is the case that makes a static list feel broken.
 */
interface CallAudioDeviceController {
    /** Everything audio could currently come out of, best candidate for a call first. */
    val devices: StateFlow<List<CallAudioDevice>>

    /**
     * Where audio is going now, or null before [start].
     *
     * Tracked here rather than read back from the platform on demand: `AudioManager` has no
     * "which route did you give me" that answers consistently across API levels.
     */
    val selectedDevice: StateFlow<CallAudioDevice?>

    /**
     * Take the device into communication mode, start watching for devices appearing and
     * disappearing, and route to the best available candidate.
     *
     * @param preferLoudspeaker which of the phone's own two outputs counts as best when nothing is
     * plugged in or paired. False is a voice call held to the head, so the earpiece; true is a call
     * being looked at, a video call, so the loudspeaker. A headset of any kind outranks both either
     * way, because reaching for one is itself the instruction.
     */
    fun start(preferLoudspeaker: Boolean)

    /** Send call audio to [device]. No effect if it is no longer connected. */
    fun select(device: CallAudioDevice)

    /**
     * Whether the phone might currently be against the user's ear.
     *
     * Gates the proximity wake lock, which blanks the screen when the sensor is covered. That lock
     * exists for exactly one purpose - stopping a cheek from operating the screen during a call held
     * to the face - and outside that situation it is actively harmful: the sensor sits beside the
     * earpiece at the top of the phone, so a hand reaching for anything near the top of the screen
     * covers it and the display goes black under the user's finger.
     *
     * The earpiece being selected is necessary but nowhere near sufficient, and the caller is the only
     * one that can judge the rest - whether there is video worth looking at, whether the call is even
     * the thing on screen. Defaults to false: blanking nothing is a much smaller failure than blanking
     * a screen somebody is using.
     */
    fun setProximityBlankingAllowed(allowed: Boolean)

    /** Hand the routing, the audio mode and the proximity sensor back to the system. */
    fun stop()
}
