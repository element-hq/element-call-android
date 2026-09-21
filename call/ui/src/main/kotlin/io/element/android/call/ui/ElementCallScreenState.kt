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
import io.element.android.call.api.rtc.MatrixRtcParticipant
import io.element.android.call.api.rtc.MatrixRtcReceiveStats
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow

data class ElementCallScreenState(
    val connection: ElementCallConnection,
    /**
     * Members the RTC core sees in the slot, including us. Known before media connects, and in every
     * compatibility mode - the core is fed a membership in all three.
     *
     * Zero until the first snapshot arrives, which on a call we have joined is a passing state rather
     * than a claim that the call is empty. [participants] is the transport's answer to the same
     * question: later, but derived from media actually flowing.
     */
    val memberCount: Int,
    /** Everyone the media session sees in the call, us included. Empty until media connects. */
    val participants: ImmutableList<MatrixRtcParticipant>,
    /** Meter readings by member id, ours included: what we capture and what we decode. */
    val audioLevels: ImmutableMap<String, MatrixRtcAudioLevel>,
    /**
     * RTP receive counters by member id, for remote members only. Empty until the transport's first
     * report, so a member missing here is not yet known rather than receiving nothing.
     */
    val receiveStats: ImmutableMap<String, MatrixRtcReceiveStats>,
    /**
     * Last per-member frame-encryption state the core has reported.
     *
     * A member absent from this map is one the core has said nothing about since we connected, which is
     * not the same as one it reports as fine - and says nothing at all about the wire, which the transport
     * encrypts to the SFU either way. This is only about media being *also* encrypted end to end.
     */
    val frameEncryption: ImmutableMap<String, MatrixRtcFrameEncryptionState>,
    /**
     * Who the transport currently hears, by member id.
     *
     * The SFU derives this from the RTP audio level header, which end-to-end encryption leaves
     * readable - so a member can be an active speaker here while [audioLevels] shows us nothing,
     * which is precisely what a broken key looks like.
     */
    val activeSpeakerIds: ImmutableSet<String>,
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
     * Video by member id, for the members currently publishing a camera - ourselves included. Empty
     * before media connects, and a member absent from it has no video to show.
     *
     * Flows rather than the latest frames, which is the unusual part of this state class and is
     * deliberate: a frame held in state would recompose the whole screen thirty times a second. Each
     * flow is a stable reference for as long as that member is in the call, and only their tile
     * collects it - which is also what decides whether their video is decoded at all.
     */
    val videoFrames: ImmutableMap<String, Flow<MatrixRtcVideoFrame>>,
    /** The room's display name, or null until it has been read. */
    val roomName: String?,
    /** Whether the room is a DM. False until the room has been read. See [layout]. */
    val isDm: Boolean,
    /** When media first connected, on the elapsed-realtime clock, or null while connecting. */
    val connectedAtElapsedMs: Long?,
    /**
     * [participants], joined with the room so they have names and faces, in the order they are drawn.
     *
     * Kept alongside the raw [participants] rather than replacing them: the diagnostics screen wants
     * the RTC layer's own view, unmixed with anything the room says, because telling those two apart
     * is the point of it.
     */
    val tiles: ImmutableList<CallParticipant>,
    /** Who the controller has settled on for the big tile. See `ElementCallSnapshot.spotlightMemberId`. */
    val spotlightMemberId: String?,
    /**
     * What the overflow menu shows: the library version and the core it was built against. Carried in
     * state rather than read from `ElementCallVersion` where they are drawn, so previews and screenshots
     * show a fixed value rather than one that changes with every release.
     */
    val libraryVersion: String,
    val coreVersion: String,
    val eventSink: (ElementCallScreenEvent) -> Unit,
) {
    /**
     * Who gets the big tile: a shared screen if there is one, else whoever the SFU currently hears,
     * else any remote member.
     *
     * A screen wins outright and is not subject to the speaker hysteresis. Somebody shares a screen
     * *in order for it to be looked at*, and a call where the spotlight flicks off it every time
     * someone speaks would be actively worse than one that never moved at all.
     *
     * **Never ourselves**, and null rather than falling back to us when we are alone. We are already
     * in the strip, so spotlighting us draws the same person twice - which reads as a bug, and is one
     * on the video path too, since it means two tiles collecting one member's frames.
     *
     * The design agrees: the spotlight is always someone else, and "You" only ever appears in the
     * strip. A call with nobody else in it yet has nobody to spotlight, and says so by showing only
     * the strip.
     */
    val spotlightParticipant: CallParticipant?
        get() = tiles.firstOrNull { it.isScreenShare }
            ?: tiles.firstOrNull { it.memberId == spotlightMemberId && !it.isLocal }
            ?: tiles.firstOrNull { !it.isLocal }

    /**
     * Everyone the strip below the spotlight shows: everyone *except* whoever is in the spotlight.
     *
     * The mockup does draw the spotlighted member a second time in the strip, with a highlight to
     * say that is who is spotlighted. On a real two-party call that reads as a glitch rather than as
     * a highlight - the same face, twice, one above the other - so they are excluded here. Put them
     * back by using [tiles] instead if the strip ever grows enough for the highlight to make sense.
     */
    val stripParticipants: ImmutableList<CallParticipant>
        get() = tiles.filterNot { it.tileId == spotlightParticipant?.tileId }.toImmutableList()

    /**
     * Which arrangement the screen draws: the other person full-bleed with us as a thumbnail, or the
     * spotlight and strip.
     *
     * One-to-one is only for a DM with exactly the two of us in it, each on a single camera tile.
     * Every other shape falls back to the group layout on purpose, and each clause is one of them:
     * alone before the other side has joined there is nobody to fill the screen with; a third member
     * has nowhere to go in a two-tile layout; and a shared screen is a third tile, which the group
     * layout already knows to spotlight. Our own share never makes a tile, so sharing *from* a DM
     * stays one-to-one.
     *
     * Derived rather than stored so it can never disagree with [tiles], and so the flip between the
     * two is a change of rectangles for the same tiles rather than a change of screen.
     */
    val layout: CallLayout
        get() = if (isDm && hasOneToOneTiles) CallLayout.OneToOne else CallLayout.Group

    /** Exactly the two of us, each on a single camera tile. */
    private val hasOneToOneTiles: Boolean
        get() = tiles.size == 2 && tiles.count { it.isLocal } == 1 && tiles.none { it.isScreenShare }
}

/** How the tiles are arranged. See [ElementCallScreenState.layout]. */
enum class CallLayout {
    /** The other person fills the screen, we are a thumbnail over them. */
    OneToOne,

    /** Whoever is talking large, everyone else in a strip. */
    Group,
}
