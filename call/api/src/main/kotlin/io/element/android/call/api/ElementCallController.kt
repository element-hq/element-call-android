/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api

import io.element.android.call.api.audio.CallAudioDevice
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
     * Whether leaving the app should shrink the call into a floating window: a maximized call, or a
     * minimized one with a picture in it. A minimized audio call is excluded, the ongoing-call
     * notification is the right affordance for it.
     */
    val shouldEnterPictureInPicture: StateFlow<Boolean>

    /** Whether the app is currently a floating window, which only the Activity is told. */
    val isInPictureInPicture: StateFlow<Boolean>

    fun setInPictureInPicture(isInPictureInPicture: Boolean)

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

    /** Show or hide the per-tile debug readout. See [ElementCallSnapshot.isTileStatsVisible]. */
    fun toggleTileStats()

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

    /** Tell the call which tiles the screen composes, so it only polls statistics for those. See [MatrixRtcCall.setComposedTiles]. */
    fun setComposedTiles(tileIds: Set<MatrixRtcTileId>)

    /** End the call and publish a leave membership. */
    fun hangUp()
}
