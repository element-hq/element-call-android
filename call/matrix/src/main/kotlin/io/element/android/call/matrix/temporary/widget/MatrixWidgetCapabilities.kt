/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.api.widget

/**
 * What a widget is allowed to do, as granted by the host through [MatrixWidgetDriver].
 *
 * The SDK's widget machine stores whatever the host grants, without intersecting it with what the widget
 * asked for, so this is the whole permission set rather than an answer to a request.
 */
data class MatrixWidgetCapabilities(
    /** Events the widget may read: state (initial push and live updates) and to-device messages. */
    val read: List<MatrixWidgetEventFilter>,
    /** Events the widget may send: state, message-like and to-device. */
    val send: List<MatrixWidgetEventFilter>,
    /** Whether the widget must be shown inside a client rather than in a standalone browser (`io.element.requires_client`). */
    val requiresClient: Boolean = false,
    /** MSC4157: whether the widget may cancel or restart a delayed event. */
    val updateDelayedEvent: Boolean = false,
    /** MSC4157: whether the widget may send a delayed event. */
    val sendDelayedEvent: Boolean = false,
)

/**
 * One MSC2762 / MSC3819 event filter, in the SDK's own vocabulary.
 */
sealed interface MatrixWidgetEventFilter {
    /** A message-like event of the given type. */
    data class MessageLikeWithType(val eventType: String) : MatrixWidgetEventFilter

    /** An `m.room.message` event with the given `msgtype`. */
    data class RoomMessageWithMsgtype(val msgtype: String) : MatrixWidgetEventFilter

    /** A state event of the given type, whatever its state key. */
    data class StateWithType(val eventType: String) : MatrixWidgetEventFilter

    /** A state event of the given type and state key. */
    data class StateWithTypeAndStateKey(val eventType: String, val stateKey: String) : MatrixWidgetEventFilter

    /** A to-device message of the given type. */
    data class ToDevice(val eventType: String) : MatrixWidgetEventFilter
}
