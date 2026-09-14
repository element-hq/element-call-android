/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.callnative.impl.ui

import io.element.android.libraries.audio.api.CallAudioDevice
import io.element.android.libraries.matrixrtc.api.MatrixRtcStreamKind
import io.element.android.libraries.matrixrtc.api.MatrixRtcVideoConstraints

sealed interface NativeCallEvent {
    data class SetMicrophonePermissionGranted(val granted: Boolean) : NativeCallEvent

    /**
     * Unlike the microphone this arrives mid-call, because the camera is only asked for when the user
     * first reaches for it.
     */
    data class SetCameraPermissionGranted(val granted: Boolean) : NativeCallEvent

    data object ToggleMicrophoneMuted : NativeCallEvent

    /** Start or stop the camera, asking for the permission first if we have never had it. */
    data object ToggleCamera : NativeCallEvent

    /** Swap the front and back cameras. */
    data object SwitchCamera : NativeCallEvent

    /**
     * Show or hide the per-tile debug readout.
     *
     * Reached by long-pressing any tile rather than from a settings screen, because the questions it
     * answers - is this the layer we asked for, is the network dropping it - come up *during* a call
     * and are gone by the time anyone has navigated away and back.
     */
    data object ToggleTileStats : NativeCallEvent

    /**
     * Report how large a member's video is actually being drawn, so the SFU can send a layer that
     * size rather than its best one. Sent by the tile that draws it, which is the only thing that
     * knows.
     */
    data class SetVideoConstraints(
        val memberId: String,
        val kind: MatrixRtcStreamKind,
        val constraints: MatrixRtcVideoConstraints,
    ) : NativeCallEvent

    /**
     * Start or stop sharing the screen.
     *
     * Starting always goes through the system's capture dialog, because the grant is one-shot and
     * there is nothing to remember between shares.
     */
    data object ToggleScreenShare : NativeCallEvent

    data object ToggleAudioTestTone : NativeCallEvent

    /** Send call audio to a specific device, chosen from `NativeCallState.audioDevices`. */
    data class SelectAudioDevice(val device: CallAudioDevice) : NativeCallEvent

    /**
     * Dock the call into the minimized bar, leaving it running.
     *
     * Not a way of ending the call, and deliberately not bound to the back gesture alone: the call
     * carries on, and a user who meant to leave it needs [HangUp] to be the obvious other option.
     */
    data object Minimize : NativeCallEvent

    data object HangUp : NativeCallEvent
}
