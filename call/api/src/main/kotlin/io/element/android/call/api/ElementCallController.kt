/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api

import io.element.android.call.api.audio.CallAudioDevice
import io.element.android.call.api.rtc.MatrixRtcDetailWindow
import io.element.android.call.api.rtc.MatrixRtcReaction
import io.element.android.call.api.rtc.MatrixRtcReactionKind
import io.element.android.call.api.rtc.MatrixRtcScreenCaptureToken
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.MatrixRtcVideoConstraints
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The one native call that can be running, for as long as it runs. Obtained from `ElementCallStack`.
 *
 * A call is not a screen: it is held here, on the session's scope, so that the full-screen UI and the
 * minimized bar are two renderings of the same thing and the call survives both (spec rule R8: it works
 * with no UI attached at all). Every method returns immediately and takes effect on the controller's
 * scope; the UI only ever sees whole [ElementCallSnapshot]s.
 *
 * Single call by construction: [startCall] refuses while one is running.
 */
interface ElementCallController {
    /** The call currently running, or null when there is none. */
    val state: StateFlow<ElementCallSnapshot?>

    /**
     * Whether leaving the call screen should shrink it into a floating window: while it is maximized.
     * A minimized call is in the host's bar, and the ongoing-call notification is the affordance for it.
     */
    val shouldEnterPictureInPicture: StateFlow<Boolean>

    /** Whether the app is currently a floating window, which only the Activity is told. */
    val isInPictureInPicture: StateFlow<Boolean>

    fun setInPictureInPicture(isInPictureInPicture: Boolean)

    /**
     * Where the tile the floating window will show is drawn in the app, or null when it is not on
     * screen. The window takes its shape and its way in from it, so that leaving the app shrinks
     * that tile rather than the whole screen. Frozen while in picture-in-picture, where nothing
     * draws the tile and the window must keep its shape.
     */
    val pictureInPictureSource: StateFlow<ElementCallWindowRect?>

    /** Sent by the screen, which is the only thing that knows where the tile is. See [pictureInPictureSource]. */
    fun setPictureInPictureSource(source: ElementCallWindowRect?)

    /**
     * Where the system is about to shrink the app from into the floating window, while it is on its
     * way in, and null otherwise. The screen draws the window's content there, so that what shrinks
     * is what the window shows rather than the layout around the tile.
     */
    val pictureInPictureEntry: StateFlow<ElementCallWindowRect?>

    /** Sent by `ElementCallPictureInPicture`, which is told when the app is leaving. See [pictureInPictureEntry]. */
    fun setPictureInPictureEntry(entry: ElementCallWindowRect?)

    /**
     * Begin a call. Does nothing if one is already running, including for the same room.
     *
     * Nothing happens beyond [ElementCallConnection.RequestingPermission] until the host answers with
     * [setMicrophonePermissionGranted]: only an Activity can ask for a permission, and this is not one.
     */
    fun startCall(callData: ElementCallData)

    /** Answer the microphone permission request. Denial fails the call. Idempotent. */
    fun setMicrophonePermissionGranted(granted: Boolean)

    /** Answer the camera permission request. A grant turns the camera on, since asking is what the user just did. */
    fun setCameraPermissionGranted(granted: Boolean)

    fun setMicrophoneMuted(muted: Boolean)

    fun setCameraEnabled(enabled: Boolean)

    fun switchCamera()

    /** Publish a fixed tone instead of the microphone, to take the capture device out of a diagnosis. */
    fun setAudioTestToneEnabled(enabled: Boolean)

    /** Send call audio to [device]. The list to choose from is [ElementCallSnapshot.audioDevices]. */
    fun selectAudioDevice(device: CallAudioDevice)

    /** Full screen or docked in the minimized bar. */
    fun setMaximized(maximized: Boolean)

    /** Show or hide the per-tile debug readout; only with [ElementCallOptions.isDeveloperModeEnabled]. See [ElementCallSnapshot.isTileStatsVisible]. */
    fun toggleTileStats()

    /** [ElementCallOptions.isDeveloperModeEnabled], for the screen. */
    val isDeveloperModeEnabled: Boolean

    /** The user's in-call preferences. Kept across calls and restarts, so readable with no call running. */
    val preferences: StateFlow<ElementCallPreferences>

    /** Applies to reactions arriving after it; what is already showing or sounding finishes (feature-hq 037 R15). */
    fun setReactionsPreference(preference: ElementCallReactionsPreference)

    /** 0 to 1 of the call's level, applied at once to every sound effect, those playing included (037 R22). */
    fun setSoundEffectsVolume(volume: Float)

    /** One short sound at the current volume, for when the user lets go of the slider (037 R24). */
    fun playSoundEffectsVolumeSample()

    /**
     * Each reaction as it should appear, unless the preference is off. Not replayed: one emitted while nothing collects,
     * in picture-in-picture or minimised, is gone, and is not shown on return (037 R8).
     */
    val reactions: Flow<MatrixRtcReaction>

    /**
     * Video frames for one member's stream, or an empty flow when no call is running. Shared: however
     * many tiles draw a member, one stream is open, and no tile drawing them means none is.
     */
    fun videoFrames(memberId: String, kind: MatrixRtcStreamKind = MatrixRtcStreamKind.CAMERA): Flow<MatrixRtcVideoFrame>

    /**
     * Start or stop sharing the screen.
     *
     * @param token the user's grant from the system dialog, which only an Activity can raise. Null
     * stops sharing.
     */
    fun setScreenShareEnabled(token: MatrixRtcScreenCaptureToken?)

    /** Tell the core how big a member's video is actually being drawn, so it sends the right layer. */
    fun setVideoConstraints(memberId: String, kind: MatrixRtcStreamKind, constraints: MatrixRtcVideoConstraints)

    /** Tell the call which tiles the screen composes, so it only polls statistics for those. See [MatrixRtcMediaSession.setComposedTiles]. */
    fun setComposedTiles(tileIds: Set<MatrixRtcTileId>)

    /**
     * Tell the call which tiles the screen needs full records for, so the roster costs what is drawn
     * rather than the whole call. Kept and replayed to a call that connects later. See
     * [MatrixRtcMediaSession.setDetailWindow].
     */
    fun setDetailWindow(window: MatrixRtcDetailWindow)

    /**
     * Below how many remote tiles the order is arrival rather than rank. Kept and applied to every
     * call that connects later. See [MatrixRtcMediaSession.setRankingThreshold].
     */
    fun setRankingThreshold(tiles: Int)

    /**
     * Raise or lower our hand. Our tile shows it at once and the core retries until it lands, however long the homeserver
     * is away; a second tap takes it back (feature-hq 034 C12). Ignored before the call has joined. Only a refusal reaches
     * [ElementCallSnapshot.handRequestFailure].
     */
    fun setHandRaised(raised: Boolean)

    /** The user has been told about [ElementCallSnapshot.handRequestFailure]. */
    fun clearHandRequestFailure()

    /** What the reaction picker offers: the core's catalogue, in its order (feature-hq 009 R11). */
    val reactionCatalogue: List<MatrixRtcReactionKind>

    /**
     * Send a reaction. Ignored before the call has joined. A refusal or a failure shows nothing (009 R12, R23); ours appears in
     * [reactions] once the core reports it, as everyone's does.
     */
    fun sendReaction(kind: MatrixRtcReactionKind)

    /**
     * From the host's Matrix client: it has gone offline (`false`), or a request has succeeded since it was (`true`). Report
     * the transition out of offline only, never sync merely starting (feature-hq 034 C3). Optional: without it the core goes
     * by its own sends, which notice an outage at the next one. See [ElementCallSnapshot.signalling].
     */
    fun reportConnectivity(isOnline: Boolean)

    /** End the call and publish a leave membership. */
    fun hangUp()
}
