/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.widget

import io.element.android.libraries.matrix.api.widget.MatrixWidgetCapabilities
import io.element.android.libraries.matrix.api.widget.MatrixWidgetEventFilter
import org.matrix.rustcomponents.sdk.WidgetCapabilities
import org.matrix.rustcomponents.sdk.WidgetEventFilter

/**
 * The SDK's view of these capabilities.
 *
 * Built by copying [requested] rather than from scratch, so that the fields this type does not model
 * keep whatever the SDK parsed from the widget's own `capabilities` answer, and so that this code names
 * no field whose presence differs between SDK releases.
 */
fun MatrixWidgetCapabilities.toRustCapabilities(requested: WidgetCapabilities): WidgetCapabilities = requested.copy(
    read = read.map { it.toRustFilter() },
    send = send.map { it.toRustFilter() },
    requiresClient = requiresClient,
    updateDelayedEvent = updateDelayedEvent,
    sendDelayedEvent = sendDelayedEvent,
)

private fun MatrixWidgetEventFilter.toRustFilter(): WidgetEventFilter = when (this) {
    is MatrixWidgetEventFilter.MessageLikeWithType -> WidgetEventFilter.MessageLikeWithType(eventType)
    is MatrixWidgetEventFilter.RoomMessageWithMsgtype -> WidgetEventFilter.RoomMessageWithMsgtype(msgtype)
    is MatrixWidgetEventFilter.StateWithType -> WidgetEventFilter.StateWithType(eventType)
    is MatrixWidgetEventFilter.StateWithTypeAndStateKey -> WidgetEventFilter.StateWithTypeAndStateKey(eventType, stateKey)
    is MatrixWidgetEventFilter.ToDevice -> WidgetEventFilter.ToDevice(eventType)
}
