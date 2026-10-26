/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallData
import io.element.android.call.api.ElementCallFailure
import io.element.android.call.api.ElementCallInterruption
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.rtc.MatrixRtcConnectionLossReason
import io.element.android.call.api.rtc.MatrixRtcParticipant
import io.element.android.call.api.rtc.id.RoomId
import kotlinx.collections.immutable.toImmutableList

open class ElementCallSnapshotPreviewParam : PreviewParameterProvider<ElementCallSnapshot> {
    override val values: Sequence<ElementCallSnapshot>
        get() = sequenceOf(
            aCallSnapshot(connection = ElementCallConnection.Joining),
            aCallSnapshot(connection = ElementCallConnection.Connected, connectedAtElapsedMs = 0L),
            aCallSnapshot(connection = ElementCallConnection.Connected, connectedAtElapsedMs = 0L, isMicrophoneMuted = true),
            aCallSnapshot(connection = ElementCallConnection.Connected, connectedAtElapsedMs = 0L, interruptions = listOf(ElementCallInterruption.OwnMedia)),
            aCallSnapshot(connection = ElementCallConnection.Failed(ElementCallFailure.ConnectionLost(MatrixRtcConnectionLossReason.BUDGET))),
            // The room name arrives asynchronously, so the bar has to survive not having one.
            aCallSnapshot(connection = ElementCallConnection.Connected, connectedAtElapsedMs = 0L, roomName = null),
            aCallSnapshot(
                connection = ElementCallConnection.Connected,
                connectedAtElapsedMs = 0L,
                roomName = "A room with a name long enough that it has to be cut short",
            ),
        )
}

fun aCallSnapshot(
    connection: ElementCallConnection = ElementCallConnection.Connected,
    roomName: String? = "Paulina",
    isMicrophoneMuted: Boolean = false,
    connectedAtElapsedMs: Long? = null,
    isMaximized: Boolean = false,
    participants: List<MatrixRtcParticipant> = emptyList(),
    speakingIds: Set<String> = emptySet(),
    interruptions: List<ElementCallInterruption> = emptyList(),
) = ElementCallSnapshot(
    callData = ElementCallData(
        roomId = RoomId("!aRoom:example.org"),
        isAudioCall = true,
        isStartingCall = true,
    ),
    connection = connection,
    roomName = roomName,
    isMicrophoneMuted = isMicrophoneMuted,
    connectedAtElapsedMs = connectedAtElapsedMs,
    isMaximized = isMaximized,
    roster = participants.previewTiles(speakingIds).previewRoster(),
    ownTile = participants.previewOwnTile(),
    interruptions = interruptions.toImmutableList(),
)
