/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import android.os.SystemClock
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallRoomMember
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.rtc.MatrixRtcTile
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.MatrixRtcTileKind
import io.element.android.call.api.rtc.id.UserId
import io.element.android.call.ui.AN_EARPIECE
import io.element.android.call.ui.A_BLUETOOTH_HEADSET
import io.element.android.call.ui.A_SPEAKER
import io.element.android.call.ui.aCallSnapshot
import io.element.android.call.ui.previewRoster
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap

/**
 * The fixtures the sample opens on, under the keys the iOS example app uses: feature-hq
 * `harness/fixtures.md` is the source of truth, and a fixture that differs from it is a review finding.
 * Previews and screenshots are not bound by it and keep their own states.
 *
 * [key] is what `--es fixture` on the launch intent takes, so an instrumented test starts where it means to;
 * [title] and [description] are what the picker shows, under [category].
 */
enum class SampleFixture(val key: String, val category: Category, val title: String, val description: String) {
    REQUESTING_PERMISSION(
        key = "requesting_permission",
        category = Category.CONNECTING,
        title = "Requesting the microphone",
        description = "Nothing happens until the permission is answered. Android only: iOS has no such state.",
    ) {
        override fun snapshot() = aCallSnapshot(connection = ElementCallConnection.RequestingPermission, isMaximized = true)
            .copy(isMicrophonePermissionGranted = false, roomMembers = roomMembersOf(listOf(ownTile(), person("Bob"))))
    },
    JOINING(
        key = "joining",
        category = Category.CONNECTING,
        title = "Joining",
        description = "Before there is a call to render.",
    ) {
        override fun snapshot() = aConnecting(ElementCallConnection.Joining)
    },
    CONNECTING_MEDIA(
        key = "connecting_media",
        category = Category.CONNECTING,
        title = "Connecting media",
        description = "Joined, waiting on media.",
    ) {
        override fun snapshot() = aConnecting(ElementCallConnection.ConnectingMedia)
    },
    FAILED(
        key = "failed",
        category = Category.CONNECTING,
        title = "Failed",
        description = "What a homeserver with no LiveKit transport looks like.",
    ) {
        override fun snapshot() = aConnecting(ElementCallConnection.Failed("Homeserver offers no LiveKit transport"))
    },
    ENDED(
        key = "ended",
        category = Category.CONNECTING,
        title = "Ended",
        description = "After hanging up, before the screen goes away.",
    ) {
        override fun snapshot() = aConnecting(ElementCallConnection.Ended)
    },
    DEGRADED(
        key = "degraded",
        category = Category.CONNECTING,
        title = "Unstable connection",
        description = "Media is up but the transport reports trouble. Android only: iOS has no such state.",
    ) {
        override fun snapshot() = aConnected(remotes = listOf(person("Bob", "v")), connection = ElementCallConnection.Degraded)
    },
    ONE_TO_ONE(
        key = "one_to_one",
        category = Category.CONNECTED,
        title = "One to one",
        description = "A direct call, our camera on, so our tile draws its flip button; Bob's draws the colour bars.",
    ) {
        override fun snapshot() = aConnected(me = "v", remotes = listOf(person("Bob", "v")), isDm = true)
    },
    GROUP(
        key = "group",
        category = Category.CONNECTED,
        title = "Group",
        description = "Eight people, nobody a hero: a grid, no spotlight.",
    ) {
        override fun snapshot() = aConnected(remotes = group())
    },
    LISTEN_MODE(
        key = "listen_mode",
        category = Category.CONNECTED,
        title = "Listen mode",
        description = "Twenty-four people: listen mode spotlights the speaker over a grid that scrolls.",
    ) {
        override fun snapshot() = aConnected(remotes = group() + crowd(LONG_GRID_CROWD))
    },
    SCREEN_SHARE(
        key = "screen_share",
        category = Category.CONNECTED,
        title = "Screen share",
        description = "A remote share holding the spotlight, the sharer's camera in the grid: two tiles, one member.",
    ) {
        override fun snapshot() = aConnected(remotes = listOf(share("Frank"), person("Frank", "v"), person("Bob", "m")))
    },
    SHARE_ON_LONG_GRID(
        key = "share_on_long_grid",
        category = Category.CONNECTED,
        title = "Sharer on a long grid",
        description = "A sharer's camera scrolls away from their screen.",
    ) {
        override fun snapshot() = aConnected(remotes = listOf(share("Frank")) + crowd(LONG_GRID_CROWD) + person("Frank", "v"))
    },
    TWO_SHARES(
        key = "two_shares",
        category = Category.CONNECTED,
        title = "Two screen shares",
        description = "Two heroes stacked in the spotlight: swipe, or the arrows in landscape, to switch.",
    ) {
        override fun snapshot() = aConnected(
            remotes = listOf(
                share("Frank"),
                share("Grace"),
                person("Frank", "v"),
                person("Carol", "!"),
                person("Bob", "m"),
                person("Dan"),
                person("Grace"),
            ),
        )
    },
    VIDEO(
        key = "video",
        category = Category.CONNECTED,
        title = "Moving video",
        description = "Real frames, Bob's and Erin's portrait and the rest landscape, with Dan's camera off beside them.",
    ) {
        override fun snapshot() = aConnected(
            me = "v",
            remotes = listOf(person("Dan"), person("Carol", "v"), person("Bob", "v"), person("Erin", "v"), person("Frank", "v")),
        )
    },
    TWO_HUNDRED(
        key = "two_hundred",
        category = Category.CONNECTED,
        title = "Two hundred",
        description = "Two hundred people, most with a camera: what a scroll costs at scale.",
    ) {
        override fun snapshot() = aConnected(
            me = "v",
            remotes = listOf(person("Carol", "!v")) +
                listOf("Bob", "Dan", "Erin", "Frank", "Grace", "Heidi").map { person(it, "v") } +
                (1..TWO_HUNDRED_CROWD).map { person("Member$it", if (it % 5 == 0) "" else "v") },
        )
    },
    MUTED(
        key = "muted",
        category = Category.CONNECTED,
        title = "Everyone muted",
        description = "Both microphones off, on a Bluetooth headset.",
    ) {
        override fun snapshot() = aConnected(me = "m", remotes = listOf(person("Bob", "m")), isDm = true)
            .copy(selectedAudioDevice = A_BLUETOOTH_HEADSET)
    },
    MINIMIZED_VOICE(
        key = "minimized_voice",
        category = Category.CONNECTED,
        title = "Minimized voice call",
        description = "Docked as a bar above the host's content. Tap it to come back.",
    ) {
        override fun snapshot() = aConnected(remotes = listOf(person("Bob")), isDm = true).copy(isMaximized = false)
    },
    MINIMIZED_VIDEO(
        key = "minimized_video",
        category = Category.CONNECTED,
        title = "Minimized video call",
        description = "Floating as a draggable tile over the host's content. Drag it, then tap it.",
    ) {
        override fun snapshot() = aConnected(remotes = listOf(person("Bob", "v")), isDm = true).copy(isMaximized = false)
    },
    ;

    /** Built when opened rather than once, so the duration counter starts from now. */
    abstract fun snapshot(): ElementCallSnapshot

    /** The picker's sections. Scenarios come after them. */
    enum class Category(val title: String) {
        CONNECTING("Connecting"),
        CONNECTED("Connected"),
    }

    companion object {
        fun fromKey(key: String): SampleFixture? = entries.firstOrNull { it.key == key }

        /** Twenty-four tiles in all: enough that the grid scrolls, and more than ten remote members. */
        private const val LONG_GRID_CROWD = 16
        private const val TWO_HUNDRED_CROWD = 192

        /** `Bob` is `@bob:example.com` with member id `@bob:example.com:DEVICE`: the people of feature-hq `harness/fixtures.md`. */
        fun userIdOf(name: String) = UserId("@${name.lowercase()}:example.com")

        fun memberIdOf(name: String) = "${userIdOf(name).value}:DEVICE"

        /** A connected call with only us in it: what a scenario's roster frames are applied over. */
        fun scenarioBase(): ElementCallSnapshot = aConnected(remotes = emptyList())

        private fun group() = listOf(
            person("Carol", "!"),
            person("Bob", "m"),
            person("Dan"),
            person("Erin"),
            person("Frank"),
            person("Grace"),
            person("Heidi"),
        )

        private fun crowd(size: Int) = (1..size).map { person("Member$it") }

        /** A person tile with the scenario grammar's flags: `*` hero, `!` speaking, `^` hand raised, `v` video, `m` muted. */
        private fun person(name: String, flags: String = "") = tile(name, MatrixRtcTileKind.PERSON, flags)

        /** A live screen share, which the core marks as a hero: `Frank#*v`. */
        private fun share(name: String) = tile(name, MatrixRtcTileKind.SCREEN_SHARE, "*v")

        private fun ownTile(flags: String = "") = tile("Alice", MatrixRtcTileKind.PERSON, flags)

        private fun tile(name: String, kind: MatrixRtcTileKind, flags: String) = MatrixRtcTile(
            id = MatrixRtcTileId(memberIdOf(name), kind),
            userId = userIdOf(name),
            deviceId = "DEVICE",
            isHero = '*' in flags,
            hasVideo = 'v' in flags,
            isMicrophoneMuted = 'm' in flags,
            isSpeaking = '!' in flags,
            handRaisedAtMs = if ('^' in flags) 1L else null,
            isReachable = true,
        )

        private fun roomMembersOf(tiles: List<MatrixRtcTile>) = tiles.associate {
            it.userId to ElementCallRoomMember(
                it.userId,
                displayName = it.userId.value.substringAfter('@').substringBefore(':').replaceFirstChar(Char::uppercase),
                avatarUrl = null,
            )
        }.toImmutableMap()

        private fun aConnecting(connection: ElementCallConnection) = aCallSnapshot(connection = connection, isMaximized = true)
            .copy(isMicrophonePermissionGranted = true, memberCount = 2, roomMembers = roomMembersOf(listOf(ownTile(), person("Bob"))))

        /** [remotes] in rank order, as the core would publish them; [me] our own flags, from `v` and `m`. */
        private fun aConnected(
            remotes: List<MatrixRtcTile>,
            me: String = "",
            isDm: Boolean = false,
            connection: ElementCallConnection = ElementCallConnection.Connected,
        ): ElementCallSnapshot {
            val own = ownTile(me)
            return aCallSnapshot(
                connection = connection,
                connectedAtElapsedMs = SystemClock.elapsedRealtime(),
                isMaximized = true,
                isMicrophoneMuted = own.isMicrophoneMuted,
            ).copy(
                roster = remotes.previewRoster(),
                ownTile = own,
                isDm = isDm,
                isMicrophonePermissionGranted = true,
                isCameraPermissionGranted = true,
                // The sample opts in, as its manifest does: it is where the share button is walked through by hand.
                isScreenShareAvailable = true,
                isCameraEnabled = own.hasVideo,
                memberCount = remotes.map { it.id.memberId }.distinct().size + 1,
                roomMembers = roomMembersOf(remotes + own),
                audioDevices = persistentListOf(AN_EARPIECE, A_SPEAKER, A_BLUETOOTH_HEADSET),
                selectedAudioDevice = AN_EARPIECE,
            )
        }
    }
}
