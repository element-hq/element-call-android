/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrixrtc.impl.bridge

import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UserId

/**
 * One room state event, with its content left as raw JSON.
 *
 * Room state has no expiry of its own. An event is only ever replaced, never removed, so a caller that
 * treats state as a membership has to read the lifetime out of [contentJson] - and a departure arrives
 * as a *present* event with empty content rather than as a shorter list.
 */
internal data class MatrixRtcRoomStateEvent(
    /** The event type as it was on the wire, which for an unstable type may be either spelling. */
    val eventType: String,
    /** The state key the event is stored under, often the empty string. */
    val stateKey: String,
    val sender: UserId,
    /** The `content` object as a JSON string. `{}` for a redacted event or a departure. */
    val contentJson: String,
    /** Null when the source does not carry one, such as stripped state. */
    val eventId: EventId?,
    /** When the event was sent, in milliseconds since the Unix epoch. Null for stripped state. */
    val timestampMs: Long?,
)
