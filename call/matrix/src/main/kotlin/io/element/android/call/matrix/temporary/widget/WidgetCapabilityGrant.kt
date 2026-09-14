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
 * The capabilities the bridge grants itself: only what the RTC core needs.
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
    /** Element Call's pre-MSC4354 membership, the one state type the bridge feeds and publishes. */
    val stateEventTypes = listOf(MatrixRtcEventTypes.MEMBER_ELEMENT_CALL_STATE_UNSTABLE)

    /** Both media-key dialects, sent and received. */
    val toDeviceEventTypes = listOf(MatrixRtcEventTypes.ENCRYPTION_KEY, MatrixRtcEventTypes.ENCRYPTION_KEY_ELEMENT_CALL)

    /** Message-like events the core may send: MSC4075 notifications and reactions. */
    val roomEventTypes = listOf(
        "org.matrix.msc4075.call.notify",
        "org.matrix.msc4310.rtc.notification",
        "m.rtc.notification",
        "io.element.call.reaction",
        "m.reaction",
    )

    /** MSC2762 / MSC3819 / MSC4157 capability strings for the same set as the grant. */
    val capabilityStrings: List<String> =
        stateEventTypes.flatMap { listOf("$RECEIVE_STATE:$it", "$SEND_STATE:$it") } +
            toDeviceEventTypes.flatMap { listOf("$RECEIVE_TO_DEVICE:$it", "$SEND_TO_DEVICE:$it") } +
            roomEventTypes.map { "$SEND_EVENT:$it" } +
            listOf(SEND_DELAYED_EVENT, UPDATE_DELAYED_EVENT)

    /**
     * Built by copying [requested] rather than from scratch, so that the fields this grant does not name
     * keep whatever the SDK parsed from the widget's own `capabilities` answer, and so that this code
     * names no field whose presence differs between SDK releases.
     */
    override fun acquireCapabilities(requested: WidgetCapabilities): WidgetCapabilities {
        val stateFilters = stateEventTypes.map { WidgetEventFilter.StateWithType(it) }
        val toDeviceFilters = toDeviceEventTypes.map { WidgetEventFilter.ToDevice(it) }
        return requested.copy(
            read = stateFilters + toDeviceFilters,
            send = stateFilters + toDeviceFilters + roomEventTypes.map { WidgetEventFilter.MessageLikeWithType(it) },
            requiresClient = false,
            updateDelayedEvent = true,
            sendDelayedEvent = true,
        )
    }

    private const val RECEIVE_STATE = "org.matrix.msc2762.receive.state_event"
    private const val SEND_STATE = "org.matrix.msc2762.send.state_event"
    private const val SEND_EVENT = "org.matrix.msc2762.send.event"
    private const val RECEIVE_TO_DEVICE = "org.matrix.msc3819.receive.to_device"
    private const val SEND_TO_DEVICE = "org.matrix.msc3819.send.to_device"
    private const val SEND_DELAYED_EVENT = "org.matrix.msc4157.send.delayed_event"
    private const val UPDATE_DELAYED_EVENT = "org.matrix.msc4157.update_delayed_event"
}
