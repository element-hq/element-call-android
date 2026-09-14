/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc

import io.element.android.call.api.rtc.id.RoomId

/**
 * Entry point to MatrixRTC for one Matrix session.
 *
 * Wraps the `matrix-rust-rtc` session manager and bridges it to the Matrix client: membership goes
 * out as MSC4354 sticky events, and sticky events, room members, encryption state and RTC
 * encryption keys are fed back in.
 */
interface MatrixRtcService {
    /**
     * Create the RTC core and bridge it to the Matrix client.
     *
     * Call this as soon as the session exists, not when a call starts. The core is meant to be the thing
     * that tells the app a call is happening - which it cannot do if it only comes into existence once
     * the user has already started one. Note that the app is not there yet: see the KDoc on the caller
     * for what is still missing before an incoming call can actually be observed.
     *
     * A second, narrower reason it must not wait for a call: the RTC encryption keys arrive over
     * to-device, and the SDK delivers each to-device message exactly once, to whoever is subscribed at
     * that moment. A key sent while nothing is subscribed is gone - there is no re-request - and the
     * symptom is a remote member stuck at `MISSING_KEY` for the rest of the call, with nothing in the
     * logs to say why.
     *
     * Idempotent: repeated calls are ignored, and [joinSession] calls it too so a caller that forgets
     * still gets a working call, just one that may have missed keys.
     */
    suspend fun start()

    /**
     * Join the RTC session identified by [roomId] and [slotId].
     *
     * @param application the MSC4143 application type, for instance `m.call`.
     * @param transport the transport to publish on, usually discovered with [discoverTransports].
     * @param elementCallCompat which generation of Element Call this call should be reachable by. Not
     * a wire-format flag that can be changed later: see [MatrixRtcElementCallCompat] for what else it
     * decides.
     * @param notify an MSC4075 notification to send with the join, which is what makes the other
     * devices in the room ring. Null - the default - joins quietly, which is what joining a call
     * someone else started does; see [MatrixRtcNotify].
     */
    suspend fun joinSession(
        roomId: RoomId,
        slotId: String,
        application: String = MatrixRtcApplication.CALL,
        transport: MatrixRtcTransport?,
        elementCallCompat: MatrixRtcElementCallCompat = MatrixRtcElementCallCompat.OFF,
        notify: MatrixRtcNotify? = null,
    ): Result<MatrixRtcSession>

    /**
     * Query the homeserver for the RTC transports it offers.
     */
    suspend fun discoverTransports(): Result<List<MatrixRtcTransport>>
}

object MatrixRtcApplication {
    const val CALL = "m.call"
}
