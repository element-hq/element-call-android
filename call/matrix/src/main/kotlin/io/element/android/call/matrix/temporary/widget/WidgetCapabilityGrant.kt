/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

// Temporary: widget-driver stopgap, see `docs/FEEDBACK.md`, "Widget-driver stopgap".

package io.element.android.call.matrix.temporary.widget

import io.element.android.call.api.rtc.MatrixRtcEventTypes
import io.element.android.call.matrix.ElementCallTemporaryApi
import org.matrix.rustcomponents.sdk.WidgetCapabilities
import org.matrix.rustcomponents.sdk.WidgetCapabilitiesProvider
import org.matrix.rustcomponents.sdk.WidgetEventFilter

/**
 * The capabilities the bridge grants itself: only what still goes through it. It sends - the delayed
 * leave (a delayed state event), the message-like room events whose ids the core keeps - and reads
 * nothing: room state and to-device messages come from the SDK.
 *
 * Not Element Call's set (`getElementCallRequiredPermissions`), which also reads `m.room.member` and would
 * push the whole member list through the pipe on every change.
 *
 * The widget machine stores whatever [acquireCapabilities] returns, without intersecting it with what the
 * widget asked for, so [capabilityStrings], answered to the `capabilities` request, and the grant describe
 * the same set.
 */
@ElementCallTemporaryApi
internal object WidgetCapabilityGrant : WidgetCapabilitiesProvider {
    /** Element Call's pre-MSC4354 membership, the one state type the bridge publishes (as a delayed leave). */
    val stateEventTypes = listOf(MatrixRtcEventTypes.MEMBER_ELEMENT_CALL_STATE_UNSTABLE)

    /**
     * Message-like events the core may send: MSC4075 notifications and reactions. The notification goes out
     * under `org.matrix.msc4075.rtc.notification` (the core's wire id for `m.rtc.notification`, and what
     * makes a call ring in the state-event compat mode); the other spellings are the older Element Call ones.
     */
    val roomEventTypes = listOf(
        "org.matrix.msc4075.rtc.notification",
        "org.matrix.msc4075.call.notify",
        "org.matrix.msc4310.rtc.notification",
        "m.rtc.notification",
        "io.element.call.reaction",
        "m.reaction",
    )

    /** MSC2762 / MSC3819 / MSC4157 capability strings for the same set as the grant. */
    val capabilityStrings: List<String> =
        stateEventTypes.map { "$SEND_STATE:$it" } +
            roomEventTypes.map { "$SEND_EVENT:$it" } +
            listOf(SEND_DELAYED_EVENT, UPDATE_DELAYED_EVENT)

    /**
     * Built by copying the requested [capabilities] rather than from scratch, so that the fields this grant
     * does not name keep whatever the SDK parsed from the widget's own `capabilities` answer, and so that
     * this code names no field whose presence differs between SDK releases.
     */
    override suspend fun acquireCapabilities(capabilities: WidgetCapabilities): WidgetCapabilities {
        return capabilities.copy(
            read = emptyList(),
            send = stateEventTypes.map { WidgetEventFilter.StateWithType(it) } + roomEventTypes.map { WidgetEventFilter.MessageLikeWithType(it) },
            requiresClient = false,
            updateDelayedEvent = true,
            sendDelayedEvent = true,
        )
    }

    private const val SEND_STATE = "org.matrix.msc2762.send.state_event"
    private const val SEND_EVENT = "org.matrix.msc2762.send.event"
    private const val SEND_DELAYED_EVENT = "org.matrix.msc4157.send.delayed_event"
    private const val UPDATE_DELAYED_EVENT = "org.matrix.msc4157.update_delayed_event"
}
