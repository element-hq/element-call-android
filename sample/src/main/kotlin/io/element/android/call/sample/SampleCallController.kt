/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import android.content.Context
import io.element.android.call.api.ElementCallController
import io.element.android.call.api.ElementCallData
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.ElementCallWindowRect
import io.element.android.call.api.audio.CallAudioDevice
import io.element.android.call.api.rtc.MatrixRtcDetailWindow
import io.element.android.call.api.rtc.MatrixRtcScreenCaptureToken
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.MatrixRtcVideoConstraints
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The one controller the sample's screen and Picture in Picture hold: the [fixtures] controller, or a ringing
 * call on the real controller while one runs, which hands back to the fixtures once it has ended.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SampleCallController(private val context: Context, private val scope: CoroutineScope) : ElementCallController {
    val fixtures = SampleElementCallController(scope)

    private var ringing: SampleRingingCall? = null
    private val current = MutableStateFlow<ElementCallController>(fixtures)
    private val target: ElementCallController get() = current.value

    override val state: StateFlow<ElementCallSnapshot?> = current.flatMapLatest { it.state }.stateIn(scope, SharingStarted.Eagerly, null)
    override val shouldEnterPictureInPicture: StateFlow<Boolean> =
        current.flatMapLatest { it.shouldEnterPictureInPicture }.stateIn(scope, SharingStarted.Eagerly, false)

    // Its own rather than the target's through stateIn, so it changes when it is set, as the real controller's does.
    private val pictureInPicture = MutableStateFlow(false)
    override val isInPictureInPicture: StateFlow<Boolean> = pictureInPicture

    /** Open a fixture, ending a ringing call first. */
    fun start(snapshot: ElementCallSnapshot) {
        endRinging()
        fixtures.start(snapshot)
    }

    fun startRinging(fixture: SampleRingingFixture) {
        endRinging()
        fixtures.hangUp()
        val call = SampleRingingCall(context, scope, fixture)
        ringing = call
        current.value = call.controller
        call.start()
        scope.launch {
            // Back to the fixtures once the call has come and gone, sounds included.
            call.controller.state.dropWhile { it == null }.first { it == null }
            if (ringing === call) endRinging()
        }
    }

    fun endRinging() {
        val call = ringing ?: return
        ringing = null
        current.value = fixtures
        call.controller.hangUp()
        call.close()
    }

    override fun setInPictureInPicture(isInPictureInPicture: Boolean) {
        pictureInPicture.value = isInPictureInPicture
        target.setInPictureInPicture(isInPictureInPicture)
    }

    private val pictureInPictureSourceFlow = MutableStateFlow<ElementCallWindowRect?>(null)
    override val pictureInPictureSource: StateFlow<ElementCallWindowRect?> = pictureInPictureSourceFlow

    // Frozen in the window, as the real controller does, so the window keeps its shape.
    override fun setPictureInPictureSource(source: ElementCallWindowRect?) {
        if (!pictureInPicture.value) pictureInPictureSourceFlow.value = source
    }

    private val pictureInPictureEntryFlow = MutableStateFlow<ElementCallWindowRect?>(null)
    override val pictureInPictureEntry: StateFlow<ElementCallWindowRect?> = pictureInPictureEntryFlow

    override fun setPictureInPictureEntry(entry: ElementCallWindowRect?) {
        pictureInPictureEntryFlow.value = entry
    }
    override fun startCall(callData: ElementCallData) = target.startCall(callData)
    override fun setMicrophonePermissionGranted(granted: Boolean) = target.setMicrophonePermissionGranted(granted)
    override fun setCameraPermissionGranted(granted: Boolean) = target.setCameraPermissionGranted(granted)
    override fun setMicrophoneMuted(muted: Boolean) = target.setMicrophoneMuted(muted)
    override fun setCameraEnabled(enabled: Boolean) = target.setCameraEnabled(enabled)
    override fun switchCamera() = target.switchCamera()
    override fun setAudioTestToneEnabled(enabled: Boolean) = target.setAudioTestToneEnabled(enabled)
    override fun selectAudioDevice(device: CallAudioDevice) = target.selectAudioDevice(device)
    override fun setMaximized(maximized: Boolean) = target.setMaximized(maximized)
    override fun toggleTileStats() = target.toggleTileStats()
    override fun videoFrames(memberId: String, kind: MatrixRtcStreamKind): Flow<MatrixRtcVideoFrame> = target.videoFrames(memberId, kind)
    override fun setScreenShareEnabled(token: MatrixRtcScreenCaptureToken?) = target.setScreenShareEnabled(token)
    override fun setVideoConstraints(memberId: String, kind: MatrixRtcStreamKind, constraints: MatrixRtcVideoConstraints) =
        target.setVideoConstraints(memberId, kind, constraints)
    override fun setComposedTiles(tileIds: Set<MatrixRtcTileId>) = target.setComposedTiles(tileIds)
    override fun setDetailWindow(window: MatrixRtcDetailWindow) = target.setDetailWindow(window)
    override fun setRankingThreshold(tiles: Int) = target.setRankingThreshold(tiles)
    override fun setHandRaised(raised: Boolean) = target.setHandRaised(raised)
    override fun clearHandRequestFailure() = target.clearHandRequestFailure()
    override fun hangUp() = target.hangUp()
}
