/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.matrix

import io.element.android.call.api.rtc.id.EventId
import io.element.android.call.api.rtc.id.UserId

/**
 * A room event as the client received it, content untouched: a sticky event, a state event or a
 * timeline event. The RTC core parses it; nothing on the way does.
 */
data class ElementCallRoomEvent(
    val eventId: EventId,
    val sender: UserId,
    /** The event type as it was on the wire, which for an unstable type may be either spelling. */
    val eventType: String,
    /** Set for a state event, often to the empty string; null otherwise. */
    val stateKey: String?,
    /** `origin_server_ts`, in milliseconds since the Unix epoch. */
    val timestampMs: Long,
    /** The whole `content` object as a JSON string, decrypted. `{}` for a redacted event or a departure. */
    val contentJson: String,
    /** Encryption data, or null if the event was sent in the clear. */
    val encryptionInfo: ElementCallEventEncryptionInfo?,
)
