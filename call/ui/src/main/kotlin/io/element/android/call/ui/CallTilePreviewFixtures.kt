/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import io.element.android.call.api.rtc.MatrixRtcParticipant
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcTile
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.MatrixRtcTileKind
import io.element.android.call.api.rtc.MatrixRtcTileRef
import io.element.android.call.api.rtc.MatrixRtcTileRoster
import io.element.android.call.api.rtc.personTile

/**
 * The remote tiles the core would publish for these participants, for previews and fixtures, in the
 * contract's bands: shares as heroes, then speakers, then cameras on, then everyone else, in the order
 * given within each band. Not the core's ranking - no hysteresis, no hands - only its shape.
 */
fun List<MatrixRtcParticipant>.previewTiles(speakingIds: Set<String> = emptySet()): List<MatrixRtcTile> {
    val remotes = filterNot { it.isLocal }
    val shares = remotes
        .filter { participant -> participant.streams.any { it.kind == MatrixRtcStreamKind.SCREEN_SHARE } }
        .map { participant ->
            participant.personTile().copy(
                id = MatrixRtcTileId(participant.memberId, MatrixRtcTileKind.SCREEN_SHARE),
                isHero = true,
                hasVideo = participant.streams.any { it.kind == MatrixRtcStreamKind.SCREEN_SHARE && !it.isMuted },
            )
        }
    val cameras = remotes
        .map { it.personTile().copy(isSpeaking = it.memberId in speakingIds) }
        .sortedWith(compareByDescending<MatrixRtcTile> { it.isSpeaking }.thenByDescending { it.hasVideo })
    return shares + cameras
}

/** These tiles as a roster in the order given, with detail for every one: the core's default window. */
fun List<MatrixRtcTile>.previewRoster() = MatrixRtcTileRoster(
    order = map { MatrixRtcTileRef(it.id, it.userId, it.isHero) },
    detail = associateBy { it.id },
)

/** Our own tile for these participants, as the core would publish it. */
fun List<MatrixRtcParticipant>.previewOwnTile(): MatrixRtcTile? = firstOrNull { it.isLocal }?.personTile()
