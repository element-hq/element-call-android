/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.test.scenario

import io.element.android.call.api.rtc.MatrixRtcLocalState
import io.element.android.call.api.rtc.MatrixRtcTile
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.MatrixRtcTileKind
import io.element.android.call.api.rtc.MatrixRtcTileRoster
import io.element.android.call.api.rtc.id.UserId
import io.element.android.call.test.FakeMatrixRtcMediaSession
import io.element.android.call.test.FakeMatrixRtcService

/**
 * Plays a scenario's roster frames into the call a [FakeMatrixRtcService] handed out, as the core
 * would publish them: the order whole, detail narrowed to what a `detail-only` frame allows and
 * then to the declared window (the fake call does that join). Records nothing itself; the fake
 * call records the window and the constraints the layout sends.
 *
 * Our own tile is published through the local state from the start, so the stage has us to draw
 * (spec 003 R1); `me` frames are the controller's to apply, since they are what the user did.
 */
class ScriptedMatrixRtcSession(private val service: FakeMatrixRtcService) {
    private var detailOnly: Set<MatrixRtcTileId>? = null

    /** The connected call, once the controller has connected media. */
    val call: FakeMatrixRtcMediaSession
        get() = checkNotNull(service.lastSession?.lastCall) { "No call connected yet" }

    /** The roster last pushed, before any window narrowed it. */
    var lastRoster: MatrixRtcTileRoster = MatrixRtcTileRoster.EMPTY
        private set

    /** Publish our own tile, which the core does once our membership reaches its roster. */
    fun start() {
        call.localState.value = MatrixRtcLocalState(tile = ownTile(), isScreenSharing = false)
    }

    /** Apply a roster or a `detail-only` frame; every other action is the screen's or the controller's. */
    fun apply(content: ScenarioContent) {
        when (content) {
            is ScenarioContent.Roster -> {
                lastRoster = content.toRoster(detailOnly)
                call.pushRoster(lastRoster)
            }
            is ScenarioContent.Action -> if (content.action is ScenarioAction.DetailOnly) {
                detailOnly = content.action.tiles.map { it.id }.toSet()
                // Narrowing applies to the next rosters; the current one is republished so the
                // effect is observable at the frame that asked for it.
                lastRoster = MatrixRtcTileRoster(order = lastRoster.order, detail = lastRoster.detail.filterKeys { it in checkNotNull(detailOnly) })
                call.pushRoster(lastRoster)
            }
        }
    }

    private fun ownTile() = MatrixRtcTile(
        id = MatrixRtcTileId(call.localMemberId, MatrixRtcTileKind.PERSON),
        userId = OWN_USER_ID,
        deviceId = MatrixRtcScenario.DEVICE,
        isHero = false,
        hasVideo = false,
        isMicrophoneMuted = false,
        isSpeaking = false,
        handRaisedAtMs = null,
        isReachable = true,
    )

    companion object {
        val OWN_USER_ID = UserId("@me:example.com")
    }
}
