/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import io.element.android.call.api.audio.CallAudioDevice
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.MatrixRtcVideoConstraints

sealed interface ElementCallScreenEvent {
    data class SetMicrophonePermissionGranted(val granted: Boolean) : ElementCallScreenEvent

    /**
     * Unlike the microphone this arrives mid-call, because the camera is only asked for when the user
     * first reaches for it.
     */
    data class SetCameraPermissionGranted(val granted: Boolean) : ElementCallScreenEvent

    data object ToggleMicrophoneMuted : ElementCallScreenEvent

    /** Start or stop the camera, asking for the permission first if we have never had it. */
    data object ToggleCamera : ElementCallScreenEvent

    /** Swap the front and back cameras. */
    data object SwitchCamera : ElementCallScreenEvent

    /**
     * Show or hide the per-tile debug readout.
     *
     * Reached by long-pressing any tile rather than from a settings screen, because the questions it
     * answers - is this the layer we asked for, is the network dropping it - come up *during* a call
     * and are gone by the time anyone has navigated away and back.
     */
    data object ToggleTileStats : ElementCallScreenEvent

    /**
     * Report how large a member's video is actually being drawn, so the SFU can send a layer that
     * size rather than its best one. Sent by the tile that draws it, which is the only thing that
     * knows.
     */
    data class SetVideoConstraints(
        val memberId: String,
        val kind: MatrixRtcStreamKind,
        val constraints: MatrixRtcVideoConstraints,
    ) : ElementCallScreenEvent

    /**
     * Which tiles the layout composes - on screen or within a page of it. Sent by the layout, which
     * is the only thing that knows, whenever the set changes; the call polls statistics for these.
     */
    data class SetComposedTiles(val tileIds: Set<MatrixRtcTileId>) : ElementCallScreenEvent

    /**
     * Start or stop sharing the screen.
     *
     * Starting always goes through the system's capture dialog, because the grant is one-shot and
     * there is nothing to remember between shares.
     */
    data object ToggleScreenShare : ElementCallScreenEvent

    data object ToggleAudioTestTone : ElementCallScreenEvent

    /** Send call audio to a specific device, chosen from `ElementCallScreenState.audioDevices`. */
    data class SelectAudioDevice(val device: CallAudioDevice) : ElementCallScreenEvent

    /**
     * Dock the call into the minimized bar, leaving it running.
     *
     * Not a way of ending the call, and deliberately not bound to the back gesture alone: the call
     * carries on, and a user who meant to leave it needs [HangUp] to be the obvious other option.
     */
    data object Minimize : ElementCallScreenEvent

    data object HangUp : ElementCallScreenEvent
}
