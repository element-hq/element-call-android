/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.test.scenario

import io.element.android.call.api.rtc.MatrixRtcTile
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.MatrixRtcTileKind
import io.element.android.call.api.rtc.MatrixRtcTileRef
import io.element.android.call.api.rtc.MatrixRtcTileRoster
import io.element.android.call.api.rtc.id.UserId

/**
 * A layout scenario: the order the core would publish, frame by frame, and what the user does in
 * between. The grammar is `plans/003.call_layout/scenarios/README.md` in feature-hq; the files
 * under `resources/scenarios/` are vendored from there, and a copy that differs is a review finding.
 *
 * A scenario states the core's *output*, never the events that produce it: the ranking, its
 * damping and its coalescing are the model's, and a harness that re-implemented them would be a
 * second ranking free to drift from the first.
 */
data class MatrixRtcScenario(val name: String, val frames: List<ScenarioFrame>) {
    companion object {
        /** Parses the grammar. Throws with the line number on anything it does not understand. */
        fun parse(name: String, text: String): MatrixRtcScenario = MatrixRtcScenario(name, ScenarioParser.parse(text))

        /** One of the vendored files, by its name without `.txt`. */
        fun load(name: String): MatrixRtcScenario {
            val text = checkNotNull(MatrixRtcScenario::class.java.getResourceAsStream("/scenarios/$name.txt")) { "No scenario $name" }
                .bufferedReader()
                .readText()
            return parse(name, text)
        }

        /** The user id and member id a scenario's member name maps to, matching the preview fixtures. */
        fun userIdOf(member: String) = UserId("@${member.lowercase()}:example.com")

        fun memberIdOf(member: String) = "@$member:example.com:$DEVICE"
        const val DEVICE = "DEVICE"

        /** A tile as a scenario token: the name, `#` for a share, and the flags the record carries. */
        fun tokenOf(ref: MatrixRtcTileRef, detail: MatrixRtcTile?): String {
            val name = ref.id.memberId.removePrefix("@").substringBefore(":")
            return buildString {
                append(name)
                if (ref.id.kind == MatrixRtcTileKind.SCREEN_SHARE) append('#')
                if (ref.isHero) append('*')
                if (detail != null) {
                    if (detail.isSpeaking) append('!')
                    if (detail.handRaisedAtMs != null) append('^')
                    if (detail.hasVideo) append('v')
                    if (detail.isMicrophoneMuted) append('m')
                }
            }
        }
    }
}

/** One line of a scenario: at [timeMs], a roster or an action. [source] is the line as written, for the dump's header. */
data class ScenarioFrame(val timeMs: Long, val content: ScenarioContent, val source: String)

sealed interface ScenarioContent {
    /** The remote tiles in rank order, with the flags each carries. Our own tile is implicit. */
    data class Roster(val tiles: List<ScenarioTile>) : ScenarioContent

    data class Action(val action: ScenarioAction) : ScenarioContent
}

data class ScenarioTile(
    val member: String,
    val isShare: Boolean,
    val isHero: Boolean,
    val isSpeaking: Boolean,
    val isHandRaised: Boolean,
    val hasVideo: Boolean,
    val isMuted: Boolean,
) {
    val id: MatrixRtcTileId
        get() = MatrixRtcTileId(MatrixRtcScenario.memberIdOf(member), if (isShare) MatrixRtcTileKind.SCREEN_SHARE else MatrixRtcTileKind.PERSON)

    fun toTile() = MatrixRtcTile(
        id = id,
        userId = MatrixRtcScenario.userIdOf(member),
        deviceId = MatrixRtcScenario.DEVICE,
        isHero = isHero,
        hasVideo = hasVideo,
        isMicrophoneMuted = isMuted,
        isSpeaking = isSpeaking,
        handRaisedAtMs = if (isHandRaised) HAND_RAISED_AT_MS else null,
        isReachable = true,
    )

    private companion object {
        const val HAND_RAISED_AT_MS = 1_000L
    }
}

/** A roster frame as the core would publish it, with detail for every tile unless [detailOnly] narrows it. */
fun ScenarioContent.Roster.toRoster(detailOnly: Set<MatrixRtcTileId>? = null): MatrixRtcTileRoster {
    val all = tiles.map { it.toTile() }
    return MatrixRtcTileRoster(
        order = all.map { MatrixRtcTileRef(it.id, it.userId, it.isHero) },
        detail = all.filter { detailOnly == null || it.id in detailOnly }.associateBy { it.id },
    )
}

sealed interface ScenarioAction {
    data class Viewport(val width: Int, val height: Int, val bottomInset: Int, val topInset: Int) : ScenarioAction

    data class Scroll(val offset: Float) : ScenarioAction

    data class Rotate(val isLandscape: Boolean) : ScenarioAction

    /** Null is `none`: out of fullscreen. */
    data class Fullscreen(val tile: ScenarioTile?) : ScenarioAction

    data class SwipeHero(val next: Boolean) : ScenarioAction

    data object Minimize : ScenarioAction

    data object Restore : ScenarioAction

    data class Me(val hasVideo: Boolean, val isMuted: Boolean) : ScenarioAction

    data class DetailOnly(val tiles: List<ScenarioTile>) : ScenarioAction

    data object Tick : ScenarioAction
}

private object ScenarioParser {
    private val TOKEN = Regex("^([A-Z][A-Z0-9_]*)(#?)([*!^vm]*)$")
    private val TIME = Regex("^(\\d+(?:\\.\\d+)?)(ms|s)$")
    private val ACTIONS = setOf("viewport", "scroll", "rotate", "fullscreen", "swipe-hero", "minimize", "restore", "me", "detail-only", "tick")

    fun parse(text: String): List<ScenarioFrame> {
        var lastTime = 0L
        return text.lines().mapIndexedNotNull { index, raw ->
            val line = raw.substringBefore("//").trim()
            if (line.isEmpty()) return@mapIndexedNotNull null
            val lineNumber = index + 1
            val parts = line.split(Regex("\\s+"))
            val timeMs = parseTime(parts[0]) ?: fail(lineNumber, "expected a time, got '${parts[0]}'")
            require(timeMs >= lastTime) { "line $lineNumber: time goes backwards" }
            lastTime = timeMs
            val rest = parts.drop(1)
            val content = if (rest.firstOrNull() in ACTIONS) {
                ScenarioContent.Action(parseAction(rest, lineNumber))
            } else {
                ScenarioContent.Roster(rest.map { parseTile(it, lineNumber) })
            }
            ScenarioFrame(timeMs, content, line)
        }
    }

    private fun parseTime(text: String): Long? {
        val match = TIME.matchEntire(text) ?: return null
        val value = match.groupValues[1].toDouble()
        return if (match.groupValues[2] == "s") (value * MS_PER_S).toLong() else value.toLong()
    }

    private fun parseTile(token: String, lineNumber: Int): ScenarioTile {
        val match = TOKEN.matchEntire(token) ?: fail(lineNumber, "bad tile token '$token'")
        val flags = match.groupValues[3]
        return ScenarioTile(
            member = match.groupValues[1],
            isShare = match.groupValues[2] == "#",
            isHero = '*' in flags,
            isSpeaking = '!' in flags,
            isHandRaised = '^' in flags,
            hasVideo = 'v' in flags,
            isMuted = 'm' in flags,
        )
    }

    private fun parseAction(parts: List<String>, lineNumber: Int): ScenarioAction {
        val args = parts.drop(1)
        return when (parts[0]) {
            "viewport" -> {
                val size = args.firstOrNull() ?: fail(lineNumber, "viewport needs WxH")
                val (width, height) = size.split("x").map { it.toIntOrNull() ?: fail(lineNumber, "bad viewport '$size'") }
                ScenarioAction.Viewport(
                    width = width,
                    height = height,
                    bottomInset = inset(args, "bottom", lineNumber),
                    topInset = inset(args, "top", lineNumber),
                )
            }
            "scroll" -> ScenarioAction.Scroll(args.firstOrNull()?.toFloatOrNull() ?: fail(lineNumber, "scroll needs an offset"))
            "rotate" -> when (args.firstOrNull()) {
                "portrait" -> ScenarioAction.Rotate(isLandscape = false)
                "landscape" -> ScenarioAction.Rotate(isLandscape = true)
                else -> fail(lineNumber, "rotate needs portrait or landscape")
            }
            "fullscreen" -> when (val target = args.firstOrNull()) {
                null -> fail(lineNumber, "fullscreen needs a tile or none")
                "none" -> ScenarioAction.Fullscreen(null)
                else -> ScenarioAction.Fullscreen(parseTile(target, lineNumber))
            }
            "swipe-hero" -> when (args.firstOrNull()) {
                "next" -> ScenarioAction.SwipeHero(next = true)
                "previous" -> ScenarioAction.SwipeHero(next = false)
                else -> fail(lineNumber, "swipe-hero needs next or previous")
            }
            "minimize" -> ScenarioAction.Minimize
            "restore" -> ScenarioAction.Restore
            "me" -> {
                val flags = args.joinToString("")
                ScenarioAction.Me(hasVideo = 'v' in flags, isMuted = 'm' in flags)
            }
            "detail-only" -> ScenarioAction.DetailOnly(args.map { parseTile(it, lineNumber) })
            "tick" -> ScenarioAction.Tick
            else -> fail(lineNumber, "unknown action '${parts[0]}'")
        }
    }

    private fun inset(args: List<String>, key: String, lineNumber: Int): Int {
        val index = args.indexOf(key)
        if (index < 0) return 0
        return args.getOrNull(index + 1)?.toIntOrNull() ?: fail(lineNumber, "$key needs a number")
    }

    private fun fail(lineNumber: Int, message: String): Nothing = throw IllegalArgumentException("line $lineNumber: $message")

    private const val MS_PER_S = 1_000.0
}
