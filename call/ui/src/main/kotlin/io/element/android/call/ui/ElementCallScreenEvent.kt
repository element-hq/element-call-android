/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import io.element.android.call.api.audio.CallAudioDevice
import io.element.android.call.api.rtc.MatrixRtcDetailWindow
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
     * Which tiles the layout needs full records for: the composed band as a rank range, plus the
     * tiles it draws out of rank. Sent by the layout with [SetComposedTiles], from the same set.
     */
    data class SetDetailWindow(val window: MatrixRtcDetailWindow) : ElementCallScreenEvent

    /** Show this hero in the spotlight: a settled swipe on the stack, an arrow, or a screen reader's action. */
    data class ShowHero(val tileId: String) : ElementCallScreenEvent

    /** A double tap on a tile, or a screen reader's action: fill the stage with it, or leave if it already does (spec 000 R1, R2). */
    data class ToggleFullscreen(val tileId: String) : ElementCallScreenEvent

    /** The HUD's close button: leaves fullscreen, never the call (000 R12). */
    data object ExitFullscreen : ElementCallScreenEvent

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

/** What the stage and the screen tell the presenter about the chrome (spec 014). Theirs alone, so not a host's to send. */
internal sealed interface StageChromeEvent : ElementCallScreenEvent {
    /**
     * A single tap on the stage: after a short wait, shows or hides the fullscreen HUD or the stage's
     * chrome, whichever is in play (000 R9, 014 R14, R16). A double tap inside the wait cancels it.
     */
    data object TapStage : StageChromeEvent

    /** The stage is up, in this orientation (014 R10, R11). */
    data class StageAppeared(val isLandscape: Boolean) : StageChromeEvent

    /** The stage changed shape (014 R12). */
    data class StageRotated(val isLandscape: Boolean) : StageChromeEvent

    /** The user scrolled the grid, never the app (014 R18, R19, R23). */
    data class UserScrolled(val towardEnd: Boolean) : StageChromeEvent

    /** The user's scrolling has come to rest (014 R20). */
    data object ScrollIdle : StageChromeEvent

    /** TalkBack started or stopped (014 R24). */
    data class ScreenReaderChanged(val isRunning: Boolean) : StageChromeEvent
}

/** What the small-call layout's own tile asks for (spec 019). */
internal sealed interface SmallCallEvent : ElementCallScreenEvent {
    /** Our floating tile was released, or moved by a screen reader, into [corner] (019 R21, R25). */
    data class MoveOwnTile(val corner: ElementCallOwnTileCorner) : SmallCallEvent
}
