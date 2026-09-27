/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.test

import io.element.android.call.api.rtc.MatrixRtcParticipant
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcStreamState
import io.element.android.call.api.rtc.MatrixRtcTile
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.MatrixRtcTileKind
import io.element.android.call.api.rtc.MatrixRtcTileRef
import io.element.android.call.api.rtc.MatrixRtcTileRoster
import io.element.android.call.api.rtc.id.UserId

/** A member on a microphone and a camera, which may be muted. */
fun aCameraParticipant(
    memberId: String,
    isLocal: Boolean,
    isCameraMuted: Boolean,
    userId: UserId = UserId("@someone:example.org"),
) = MatrixRtcParticipant(
    memberId = memberId,
    userId = userId,
    deviceId = "ADEVICEID",
    isLocal = isLocal,
    isReachable = true,
    streams = listOf(
        MatrixRtcStreamState(MatrixRtcStreamKind.MICROPHONE, isMuted = false),
        MatrixRtcStreamState(MatrixRtcStreamKind.CAMERA, isMuted = isCameraMuted),
    ),
)

/** Someone with their camera on who is also sharing their screen: two video streams, one member. */
fun aSharingParticipant(
    memberId: String,
    isLocal: Boolean = false,
    userId: UserId = UserId("@someone:example.org"),
) = MatrixRtcParticipant(
    memberId = memberId,
    userId = userId,
    deviceId = "ADEVICEID",
    isLocal = isLocal,
    isReachable = true,
    streams = listOf(
        MatrixRtcStreamState(MatrixRtcStreamKind.MICROPHONE, isMuted = false),
        MatrixRtcStreamState(MatrixRtcStreamKind.CAMERA, isMuted = false),
        MatrixRtcStreamState(MatrixRtcStreamKind.SCREEN_SHARE, isMuted = false),
    ),
)

/** A tile as the core sends it. A share is a hero, as the core marks it. */
fun aTile(
    memberId: String,
    kind: MatrixRtcTileKind = MatrixRtcTileKind.PERSON,
    userId: UserId = UserId("@someone:example.org"),
    hasVideo: Boolean = true,
    isMicrophoneMuted: Boolean = false,
    isSpeaking: Boolean = false,
    isHero: Boolean = kind == MatrixRtcTileKind.SCREEN_SHARE,
) = MatrixRtcTile(
    id = MatrixRtcTileId(memberId, kind),
    userId = userId,
    deviceId = "ADEVICEID",
    isHero = isHero,
    hasVideo = hasVideo,
    isMicrophoneMuted = isMicrophoneMuted,
    isSpeaking = isSpeaking,
    handRaisedAtMs = null,
    isReachable = true,
)

/** A roster ranked in the order given, with detail for every tile: the core's default window. */
fun aRoster(vararg tiles: MatrixRtcTile) = MatrixRtcTileRoster(
    order = tiles.map { MatrixRtcTileRef(it.id, it.userId, it.isHero) },
    detail = tiles.associateBy { it.id },
)
