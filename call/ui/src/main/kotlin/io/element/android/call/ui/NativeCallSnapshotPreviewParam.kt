/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.callnative.impl.ui

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.features.call.api.CallData
import io.element.android.features.callnative.impl.NativeCallConnection
import io.element.android.features.callnative.impl.NativeCallSnapshot
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId

open class NativeCallSnapshotPreviewParam : PreviewParameterProvider<NativeCallSnapshot> {
    override val values: Sequence<NativeCallSnapshot>
        get() = sequenceOf(
            aCallSnapshot(connection = NativeCallConnection.Joining),
            aCallSnapshot(connection = NativeCallConnection.Connected, connectedAtElapsedMs = 0L),
            aCallSnapshot(connection = NativeCallConnection.Connected, connectedAtElapsedMs = 0L, isMicrophoneMuted = true),
            aCallSnapshot(connection = NativeCallConnection.Degraded, connectedAtElapsedMs = 0L),
            // The room name arrives asynchronously, so the bar has to survive not having one.
            aCallSnapshot(connection = NativeCallConnection.Connected, connectedAtElapsedMs = 0L, roomName = null),
            aCallSnapshot(
                connection = NativeCallConnection.Connected,
                connectedAtElapsedMs = 0L,
                roomName = "A room with a name long enough that it has to be cut short",
            ),
        )
}

fun aCallSnapshot(
    connection: NativeCallConnection = NativeCallConnection.Connected,
    roomName: String? = "Paulina",
    isMicrophoneMuted: Boolean = false,
    connectedAtElapsedMs: Long? = null,
    isMaximized: Boolean = false,
) = NativeCallSnapshot(
    callData = CallData(
        sessionId = SessionId("@alice:example.org"),
        roomId = RoomId("!aRoom:example.org"),
        isAudioCall = true,
    ),
    connection = connection,
    roomName = roomName,
    isMicrophoneMuted = isMicrophoneMuted,
    connectedAtElapsedMs = connectedAtElapsedMs,
    isMaximized = isMaximized,
)
