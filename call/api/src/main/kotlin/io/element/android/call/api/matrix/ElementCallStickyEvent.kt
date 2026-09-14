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
 * A sticky event currently live in a room (MSC4354).
 *
 * Sticky events expire on their own, which is what makes them a good fit for MatrixRTC membership
 * (`m.rtc.member`): a client that disappears stops refreshing its event and is dropped from the call.
 *
 * No transport produces these until the SDK bindings expose sticky events; the type, its mapper and the
 * feeder path that reads it are kept so that day is a one-line change.
 */
data class ElementCallStickyEvent(
    val sender: UserId,
    /** The event type, for instance `m.rtc.member`. */
    val eventType: String,
    /** The `content.sticky_key`, if any. */
    val stickyKey: String?,
    val eventId: EventId,
    /** Absolute expiry time, in milliseconds since the Unix epoch. */
    val expiresAtMs: Long,
    /** The full event as a JSON string, decrypted if it was sent encrypted. */
    val eventJson: String,
    /** Encryption data, or null if the event was sent in the clear. */
    val encryptionInfo: ElementCallEventEncryptionInfo?,
)
