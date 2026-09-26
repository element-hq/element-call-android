/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.audio.CallAudioDevice
import io.element.android.call.api.rtc.MatrixRtcAudioLevel
import io.element.android.call.api.rtc.MatrixRtcFrameEncryptionState
import io.element.android.call.api.rtc.MatrixRtcReceiveStats
import io.element.android.call.api.rtc.MatrixRtcStreamRef
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow

data class ElementCallScreenState(
    val connection: ElementCallConnection,
    /**
     * Members the RTC core sees in the slot, including us. Known before media connects, and in every
     * compatibility mode - the core is fed a membership in all three.
     *
     * Zero until the first snapshot arrives, which on a call we have joined is a passing state rather
     * than a claim that the call is empty.
     */
    val memberCount: Int,
    /** Meter readings by member id, ours included: what we capture and what we decode. */
    val audioLevels: ImmutableMap<String, MatrixRtcAudioLevel>,
    /**
     * RTP receive counters by remote stream - each composed tile's, and its member's microphone.
     * Empty until the transport's first report, so a stream missing here is not yet known rather than
     * receiving nothing.
     */
    val receiveStats: ImmutableMap<MatrixRtcStreamRef, MatrixRtcReceiveStats>,
    /**
     * Last per-member frame-encryption state the core has reported.
     *
     * A member absent from this map is one the core has said nothing about since we connected, which is
     * not the same as one it reports as fine - and says nothing at all about the wire, which the transport
     * encrypts to the SFU either way. This is only about media being *also* encrypted end to end.
     */
    val frameEncryption: ImmutableMap<String, MatrixRtcFrameEncryptionState>,
    val isMicrophoneMuted: Boolean,
    /** Whether we are publishing a test tone instead of the microphone. */
    val isAudioTestToneEnabled: Boolean,
    /** Everywhere call audio could come out of, best candidate first. Updates as devices come and go. */
    val audioDevices: ImmutableList<CallAudioDevice>,
    /** Where call audio is going, or null before a route has been taken. */
    val selectedAudioDevice: CallAudioDevice?,
    val isMicrophonePermissionGranted: Boolean,
    /** Whether our camera is capturing and publishing. */
    val isCameraEnabled: Boolean,
    /** Whether the camera in use faces the user, which is what decides if the self view mirrors. */
    val isFrontCamera: Boolean,
    /**
     * Whether the camera permission has been granted. False also covers "never asked" - the camera is
     * only requested when the user reaches for it, so the two are the same thing as far as the screen
     * is concerned: tapping the button is what resolves either.
     */
    val isCameraPermissionGranted: Boolean,
    /** Whether the host has turned screen sharing on. False, and the bar has no share button at all. */
    val isScreenShareAvailable: Boolean,
    /**
     * Whether we are sharing our screen.
     *
     * Can turn itself off: the user can end a share from the system's cast notification, without the
     * app being involved, so the button has to follow this rather than its own memory of the last tap.
     */
    val isScreenSharing: Boolean,
    /** Whether tiles are showing their debug readout. Toggled by long-pressing a tile. */
    val isTileStatsVisible: Boolean,
    /**
     * Video by [CallTileData.tileId], for the tiles that have video - ourselves included. Empty
     * before media connects, and a tile absent from it has no video to show.
     *
     * Flows rather than the latest frames, which is the unusual part of this state class and is
     * deliberate: a frame held in state would recompose the whole screen thirty times a second. Each
     * flow is a stable reference for as long as that member is in the call, and only their tile
     * collects it - which is also what decides whether their video is decoded at all.
     */
    val videoFrames: ImmutableMap<String, Flow<MatrixRtcVideoFrame>>,
    /** The room's display name, or null until it has been read. */
    val roomName: String?,
    /** Whether the room is a DM. False until the room has been read. Decides whether the duration is shown over the stage. */
    val isDm: Boolean,
    /** When media first connected, on the elapsed-realtime clock, or null while connecting. */
    val connectedAtElapsedMs: Long?,
    /**
     * What is drawn, in the order it is drawn: our own tile first, then the core's ranking, joined with
     * the room so they have names and faces. A member sharing their screen is two of them.
     */
    val tiles: ImmutableList<CallTileData>,
    /**
     * What the spotlight shows: a hero, or in a large call with no hero the speaker, or nothing.
     * The layout's choice, never the head of the ranking (spec 003 R3). See [CallSpotlight].
     */
    val spotlight: CallSpotlight.Choice,
    /**
     * What the overflow menu shows: the library version and the core it was built against. Carried in
     * state rather than read from `ElementCallVersion` where they are drawn, so previews and screenshots
     * show a fixed value rather than one that changes with every release.
     */
    val libraryVersion: String,
    val coreVersion: String,
    val eventSink: (ElementCallScreenEvent) -> Unit,
) {
    val spotlightTileId: String? get() = spotlight.tileId

    /** The tile in the spotlight. Never ourselves (spec 003 R2). */
    val spotlightTile: CallTileData?
        get() = spotlightTileId?.let { id -> tiles.firstOrNull { it.tileId == id } }

    /** Every remote hero, in the model's order: the spotlight's stack (spec 003 R19, R20). */
    val heroes: ImmutableList<String>
        get() = tiles.filter { it.isHero && !it.isLocal }.map { it.tileId }.toImmutableList()

    /**
     * What the grid places, in order: our own tile first, then the model's order with every hero
     * and the spotlit tile removed (spec 003 R1, R17). The UI never re-sorts.
     */
    val gridTiles: ImmutableList<CallTileData>
        get() = tiles.filterNot { it.isHero || it.tileId == spotlightTileId }.toImmutableList()
}
