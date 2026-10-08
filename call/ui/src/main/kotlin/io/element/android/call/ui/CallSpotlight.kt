/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.MatrixRtcTileKind

/**
 * What is in the spotlight: a selection over the tiles, never the head of the ranking (spec 003
 * R3). A pure function of the tiles and two remembered identities.
 */
object CallSpotlight {
    /** Above this many remote members, with no hero, the speaker is spotlit (R4, R6). Shares and our own tile do not count. */
    const val LISTEN_MODE_THRESHOLD = 10

    @Immutable
    sealed interface Choice {
        val tileId: String?

        /** A hero: only ever heroes when any exists (R9); the shown one is followed by identity (R21). */
        data class Hero(override val tileId: String) : Choice

        /** Listen mode: the first tile in the order marked speaking, or the last one that was (R6, R7). */
        data class Speaker(override val tileId: String) : Choice

        data object None : Choice {
            override val tileId: String? get() = null
        }
    }

    /**
     * @param tiles what is drawn, our own tile first, in the model's order.
     * @param shownHeroId the hero shown last time, kept while it is still a hero (R20, R21).
     * @param lastSpeakerId the speaker shown last time, kept while nobody speaks (R7).
     * @param lastHeroes the stack last time, so a shown hero that left is followed by the next one
     * after its old position, else the last (R60).
     */
    fun choose(
        tiles: List<CallTileData>,
        shownHeroId: String?,
        lastSpeakerId: String?,
        lastHeroes: List<String> = emptyList(),
    ): Choice {
        // Never us (R2): our tile is spliced in with no hero flag, and is not a speaker candidate.
        val remote = tiles.filterNot { it.isLocal }
        val heroes = remote.filter { it.isHero }.map { it.tileId }
        if (heroes.isNotEmpty()) {
            val shown = when {
                shownHeroId in heroes -> shownHeroId
                // The shown hero left: the one after its old position, else the last (R60). A hero
                // arriving never changes the shown one (R20).
                shownHeroId != null && shownHeroId in lastHeroes -> {
                    val remaining = lastHeroes.filter { it in heroes }
                    val after = lastHeroes.drop(lastHeroes.indexOf(shownHeroId) + 1).firstOrNull { it in heroes }
                    after ?: remaining.lastOrNull() ?: heroes.first()
                }
                else -> heroes.first()
            }
            return Choice.Hero(checkNotNull(shown))
        }
        val people = remote.count { !it.isScreenShare }
        if (people <= LISTEN_MODE_THRESHOLD) return Choice.None
        // The model's order is already damped; nothing is held back here (R8).
        val speaking = remote.firstOrNull { !it.isScreenShare && it.isActiveSpeaker }?.tileId
        if (speaking != null) return Choice.Speaker(speaking)
        // Nobody speaking: the last speaker stays, and never the rank head (R7).
        if (lastSpeakerId != null && remote.any { it.tileId == lastSpeakerId }) return Choice.Speaker(lastSpeakerId)
        return Choice.None
    }
}

/**
 * What the spotlight remembers between rosters, and across the screen being unmounted: which hero
 * is shown, who spoke last, and what the spotlight showed, for Picture in Picture and the minimised
 * tile to follow (R7, R20, R21, R67, R68).
 *
 * Held above the screen, with `rememberSaveable`, because the screen goes away while minimised and
 * the choice has to survive both that and a rotation.
 */
@Stable
class CallSpotlightMemory internal constructor(
    shownHeroId: String?,
    lastSpeakerId: String?,
    lastHeroes: List<String>,
    spotlightId: MatrixRtcTileId?,
    ownTileCorner: ElementCallOwnTileCorner = ElementCallOwnTileCorner.Initial,
) {
    var shownHeroId: String? by mutableStateOf(shownHeroId)
        internal set
    var lastSpeakerId: String? by mutableStateOf(lastSpeakerId)
        internal set
    var lastHeroes: List<String> by mutableStateOf(lastHeroes)
        internal set

    /** The tile the spotlight showed last, as the call layer names it, or null with no spotlight. */
    var spotlightId: MatrixRtcTileId? by mutableStateOf(spotlightId)
        internal set

    /** The corner our floating tile is in, for the rest of the call: through rotation, the grid and minimising (019 R20). */
    var ownTileCorner: ElementCallOwnTileCorner by mutableStateOf(ownTileCorner)
        internal set

    internal companion object {
        val Saver: Saver<CallSpotlightMemory, List<String?>> = Saver(
            save = {
                listOf(it.shownHeroId, it.lastSpeakerId, it.spotlightId?.memberId, it.spotlightId?.kind?.name, it.ownTileCorner.name) + it.lastHeroes
            },
            restore = { saved ->
                val spotlightId = saved[2]?.let { memberId -> MatrixRtcTileId(memberId, MatrixRtcTileKind.valueOf(checkNotNull(saved[3]))) }
                CallSpotlightMemory(
                    shownHeroId = saved[0],
                    lastSpeakerId = saved[1],
                    lastHeroes = saved.drop(5).filterNotNull(),
                    spotlightId = spotlightId,
                    ownTileCorner = ElementCallOwnTileCorner.valueOf(checkNotNull(saved[4])),
                )
            },
        )
    }
}

@Composable
fun rememberCallSpotlightMemory(): CallSpotlightMemory = rememberSaveable(saver = CallSpotlightMemory.Saver) {
    CallSpotlightMemory(shownHeroId = null, lastSpeakerId = null, lastHeroes = emptyList(), spotlightId = null)
}
