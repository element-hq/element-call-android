/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.matrix

import io.element.android.call.api.rtc.id.DeviceId
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.api.rtc.id.UserId
import kotlinx.coroutines.flow.Flow

/**
 * Everything the call needs from Matrix, for one logged-in session: the port the host implements.
 *
 * Derived from the exact calls the call stack makes, and nothing more: a host implements it without
 * re-implementing a Matrix client. `element-call-matrix` ships the turnkey implementation over the
 * Rust SDK; a host with an SDK client uses that one and never sees this interface.
 *
 * Session-long: one instance per Matrix session, alive between calls. The to-device feed in
 * particular must be subscribed when nothing is in a call, because a to-device message is delivered
 * exactly once to whoever is subscribed at that moment, and RTC media keys arrive that way.
 */
interface ElementCallMatrixTransport {
    /** The session's own user. */
    val userId: UserId

    /** The session's own device. */
    val deviceId: DeviceId

    /** The homeserver's client-server API base URL, for endpoints the SDK does not wrap. */
    val homeserverUrl: String

    /** The server name of [userId], where its `.well-known` is served from. */
    fun userIdServerName(): String

    /**
     * Fetch [url] with the session's HTTP client and return the body. Used for MatrixRTC transport
     * discovery, which the SDK does not expose yet.
     */
    suspend fun getUrl(url: String): Result<String>

    /** An OpenID token proving the session's identity to the transport's authorisation service. */
    suspend fun getOpenIdToken(): Result<ElementCallOpenIdToken>

    /**
     * Open a joined room for a call. One per call: the room is opened before anything is fed or
     * joined and closed after the leave, in that order, so that whatever the implementation has to
     * bring up per room (today, the widget-driver stopgap) is ready when the join arms its delayed
     * event and still there when the leave cancels it.
     */
    suspend fun openRoom(roomId: RoomId): Result<ElementCallMatrixRoom>

    /**
     * To-device messages of the given types, for as long as the session lives.
     *
     * Only messages that arrived encrypted carry an [ElementCallToDeviceMessage.encryptionInfo]; the
     * consumer decides what to do with cleartext ones.
     */
    fun toDeviceMessages(eventTypes: Set<String>): Flow<ElementCallToDeviceMessage>

    /**
     * Send an encrypted to-device message to every listed device.
     *
     * @param messages user id -> device id -> JSON content.
     * @return the recipients that were *not* served, user id -> device ids; empty means everyone was.
     */
    suspend fun sendToDeviceMessage(eventType: String, messages: Map<UserId, Map<DeviceId, String>>): Result<Map<UserId, List<DeviceId>>>
}
