/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api

import io.element.android.call.api.audio.CallAudioDevice
import io.element.android.call.api.rtc.MatrixRtcAudioLevel
import io.element.android.call.api.rtc.MatrixRtcFrameEncryptionState
import io.element.android.call.api.rtc.MatrixRtcParticipant
import io.element.android.call.api.rtc.MatrixRtcReceiveStats
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcTile
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.id.UserId
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

/**
 * Everything known about the call currently running, as one immutable value.
 *
 * Held by [ElementCallController] rather than by a presenter, because a call outlives any one screen:
 * the same snapshot backs the full-screen call UI and the minimized bar, and survives both.
 *
 * Deliberately free of anything that needs a live handle on the RTC library - video frames in
 * particular are reached through [ElementCallController.videoFrames], so this stays a value that can
 * be constructed in a test or a preview without a call existing at all.
 */
data class ElementCallSnapshot(
    val callData: ElementCallData,
    val connection: ElementCallConnection,
    /**
     * The room's display name, or null until it has been read.
     *
     * Null is a real state rather than a defect - the name is resolved asynchronously and the call
     * bar appears before it arrives - so anything drawing this needs a fallback rather than an
     * assertion.
     */
    val roomName: String? = null,
    /**
     * Whether the room the call is in is a DM, which is what decides between the one-to-one and the
     * group layout.
     *
     * False rather than null until the room has been read: a DM drawn as a group call for the moment
     * before its info arrives is the harmless direction, and the layout animates into place once it
     * does.
     */
    val isDm: Boolean = false,
    /**
     * Room profiles by user id, for putting names and faces to [participants].
     *
     * The RTC layer only ever knows a member id and a user id, so this is the join between the call
     * and the room. Empty, or missing an entry, is normal rather than exceptional - the member list
     * loads asynchronously - so a tile has to be able to draw without one.
     */
    val roomMembers: ImmutableMap<UserId, ElementCallRoomMember> = persistentMapOf(),
    /**
     * Members the RTC core sees in the slot, including us. Known before media connects, and in every
     * compatibility mode - the core is fed a membership in all three.
     *
     * Zero until the first snapshot arrives, which on a call we have joined is a passing state rather
     * than a claim that the call is empty. [participants] is the transport's answer to the same
     * question: later, but derived from media actually flowing.
     */
    val memberCount: Int = 0,
    /** Everyone the media session sees in the call, us included. Empty until media connects. */
    val participants: ImmutableList<MatrixRtcParticipant> = persistentListOf(),
    /** Meter readings by member id, ours included: what we capture and what we decode. */
    val audioLevels: ImmutableMap<String, MatrixRtcAudioLevel> = persistentMapOf(),
    /**
     * RTP receive counters by member id, for remote members only. Empty until the transport's first
     * report, so a member missing here is not yet known rather than receiving nothing.
     */
    val receiveStats: ImmutableMap<String, MatrixRtcReceiveStats> = persistentMapOf(),
    /**
     * Last per-member frame-encryption state the core has reported.
     *
     * A member absent from this map is one the core has said nothing about since we connected, which is
     * not the same as one it reports as fine - and says nothing at all about the wire, which the transport
     * encrypts to the SFU either way. This is only about media being *also* encrypted end to end.
     */
    val frameEncryption: ImmutableMap<String, MatrixRtcFrameEncryptionState> = persistentMapOf(),
    /**
     * The remote tiles, in the core's rank order: a member sharing their screen is two of them. Our
     * own is [ownTile], never in here. Render in the order given; the core already damped it.
     */
    val tiles: ImmutableList<MatrixRtcTile> = persistentListOf(),
    /**
     * Our camera tile, or null before media connects.
     *
     * The core only publishes it once our membership reaches its roster, so until then it is built
     * from our row in [participants]: otherwise every join would open on an empty stage.
     */
    val ownTile: MatrixRtcTile? = null,
    /** Whether the microphone is muted, and before the media connects whether it will be published muted. */
    val isMicrophoneMuted: Boolean = false,
    /** Whether we are publishing a test tone instead of the microphone. */
    val isAudioTestToneEnabled: Boolean = false,
    /**
     * Everywhere call audio could come out of, best candidate first, and live: a headset paired
     * mid-call appears here without anything being asked.
     */
    val audioDevices: ImmutableList<CallAudioDevice> = persistentListOf(),
    /** Where call audio is going, or null before the route has been taken. */
    val selectedAudioDevice: CallAudioDevice? = null,
    val isMicrophonePermissionGranted: Boolean = false,
    /**
     * Whether our camera is capturing and publishing - and, until the media connects, whether it is
     * going to: the control bar is on screen throughout the join, so this is what the button reads
     * and what a tap on it changes, and the call is joined in whatever state it is left in.
     */
    val isCameraEnabled: Boolean = false,
    /** Whether the camera in use faces the user, which is what decides if the self view mirrors. */
    val isFrontCamera: Boolean = true,
    /**
     * Whether the camera permission has been granted. False also covers "never asked" - the camera is
     * only requested when the user reaches for it, so the two are the same thing as far as the screen
     * is concerned: tapping the button is what resolves either.
     */
    val isCameraPermissionGranted: Boolean = false,
    /**
     * Whether the host has turned screen sharing on, see `ElementCallOptions.isScreenSharingEnabled`.
     *
     * Fixed for the life of the call and carried here rather than read from the options, because the
     * screen only ever sees snapshots. False hides the share control altogether.
     */
    val isScreenShareAvailable: Boolean = false,
    /**
     * Whether we are capturing and publishing the screen.
     *
     * Can go false without the app asking: the user can end a share from the system's cast
     * notification, which is why this is observed from the call rather than set when we act.
     */
    val isScreenSharing: Boolean = false,
    /**
     * `SystemClock.elapsedRealtime()` at the moment media first connected, or null before that.
     *
     * Elapsed realtime rather than wall clock so a duration counted from it cannot jump when the
     * clock is corrected mid-call. Null is the honest answer while connecting: a call has no
     * duration before it has connected.
     */
    val connectedAtElapsedMs: Long? = null,
    /** Whether the call is showing full screen, as opposed to docked in the minimized bar. */
    val isMaximized: Boolean = true,
    /**
     * Whether tiles are showing their debug readout, toggled by long-pressing one.
     *
     * Deliberately per call rather than persisted: it is answering a question about *this* call, and
     * a developer who left it on last week should not be handed a screen full of numbers today.
     */
    val isTileStatsVisible: Boolean = false,
) {
    /**
     * The tile the call screen gives its big slot to, or null when nobody else is here.
     *
     * The head of [tiles]: a hero ranks first, and otherwise the core's damped ranking is already
     * the answer - a spotlight that followed raw speaker events would tear down and rebuild a video
     * tile several times a second.
     */
    val spotlightTileId: MatrixRtcTileId?
        get() = tiles.firstOrNull()?.id

    /**
     * Whether anybody in the call is sending a picture - a camera or a screen, ours or theirs.
     *
     * Three separate decisions turn on this one fact, which is why it lives here rather than in any of
     * them: a minimized call floats as a tile rather than docking as a bar, backgrounding it hands
     * over to picture-in-picture, and the proximity sensor is left alone. All three follow from the
     * same thing being true - there is something to look at, so the user is looking at the screen
     * rather than holding it against their ear.
     */
    val hasVideo: Boolean
        get() = participants.any { participant ->
            participant.streams.any {
                !it.isMuted && (it.kind == MatrixRtcStreamKind.CAMERA || it.kind == MatrixRtcStreamKind.SCREEN_SHARE)
            }
        }
}

sealed interface ElementCallConnection {
    /** Waiting for the microphone permission before anything can start. */
    data object RequestingPermission : ElementCallConnection

    data object Joining : ElementCallConnection

    data object ConnectingMedia : ElementCallConnection

    data object Connected : ElementCallConnection

    /** The transport is up but reporting trouble. */
    data object Degraded : ElementCallConnection

    data class Failed(val message: String) : ElementCallConnection

    data object Ended : ElementCallConnection
}
