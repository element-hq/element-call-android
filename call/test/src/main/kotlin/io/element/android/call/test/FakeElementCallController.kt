/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.test

import io.element.android.call.api.ElementCallController
import io.element.android.call.api.ElementCallData
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.audio.CallAudioDevice
import io.element.android.call.api.rtc.MatrixRtcScreenCaptureToken
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcStreamRef
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.MatrixRtcVideoConstraints
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

/**
 * A controller that records what it is asked and lets the test write the snapshot.
 *
 * Nothing here reacts: asking to mute does not mute. The behaviour lives in `DefaultElementCallController`
 * and is tested there; what a screen or a sample app built over this fake checks is that the right
 * request left it, and how it draws whatever [state] says.
 */
class FakeElementCallController(
    initialState: ElementCallSnapshot? = null,
) : ElementCallController {
    override val state = MutableStateFlow(initialState)
    override val shouldEnterPictureInPicture = MutableStateFlow(false)
    override val isInPictureInPicture = MutableStateFlow(false)

    val startedCalls = mutableListOf<ElementCallData>()
    val microphonePermissionAnswers = mutableListOf<Boolean>()
    val cameraPermissionAnswers = mutableListOf<Boolean>()
    val microphoneMutedCalls = mutableListOf<Boolean>()
    val cameraEnabledCalls = mutableListOf<Boolean>()
    var switchCameraCount = 0
        private set
    val audioTestToneCalls = mutableListOf<Boolean>()
    val selectedAudioDevices = mutableListOf<CallAudioDevice>()
    val maximizedCalls = mutableListOf<Boolean>()
    var toggleTileStatsCount = 0
        private set

    /** Every stream asked for, in order, so a test can see what a screen opened. */
    val videoFramesRequests = mutableListOf<MatrixRtcStreamRef>()

    /** Every share request, in order: a token to start, null to stop. */
    val screenShareTokens = mutableListOf<MatrixRtcScreenCaptureToken?>()
    val videoConstraints = mutableListOf<Pair<MatrixRtcStreamRef, MatrixRtcVideoConstraints>>()
    var hangUpCount = 0
        private set

    /**
     * One flow per stream, stable for the life of the fake, as the real controller's are: the tile
     * layout keys its collection on the instance, so a fresh flow per call would look like a stream
     * being reopened.
     */
    private val videoFlows = mutableMapOf<MatrixRtcStreamRef, Flow<MatrixRtcVideoFrame>>()

    override fun setInPictureInPicture(isInPictureInPicture: Boolean) {
        this.isInPictureInPicture.value = isInPictureInPicture
    }

    override fun startCall(callData: ElementCallData) {
        startedCalls += callData
    }

    override fun setMicrophonePermissionGranted(granted: Boolean) {
        microphonePermissionAnswers += granted
    }

    override fun setCameraPermissionGranted(granted: Boolean) {
        cameraPermissionAnswers += granted
    }

    override fun setMicrophoneMuted(muted: Boolean) {
        microphoneMutedCalls += muted
    }

    override fun setCameraEnabled(enabled: Boolean) {
        cameraEnabledCalls += enabled
    }

    override fun switchCamera() {
        switchCameraCount++
    }

    override fun setAudioTestToneEnabled(enabled: Boolean) {
        audioTestToneCalls += enabled
    }

    override fun selectAudioDevice(device: CallAudioDevice) {
        selectedAudioDevices += device
    }

    override fun setMaximized(maximized: Boolean) {
        maximizedCalls += maximized
    }

    override fun toggleTileStats() {
        toggleTileStatsCount++
    }

    override fun videoFrames(memberId: String, kind: MatrixRtcStreamKind): Flow<MatrixRtcVideoFrame> {
        val ref = MatrixRtcStreamRef(memberId, kind)
        videoFramesRequests += ref
        return videoFlows.getOrPut(ref) { flow { awaitCancellation() } }
    }

    override fun setScreenShareEnabled(token: MatrixRtcScreenCaptureToken?) {
        screenShareTokens += token
    }

    /** Every set [setComposedTiles] was given, in order. */
    val composedTiles = mutableListOf<Set<MatrixRtcTileId>>()

    override fun setComposedTiles(tileIds: Set<MatrixRtcTileId>) {
        composedTiles += tileIds
    }

    override fun setVideoConstraints(memberId: String, kind: MatrixRtcStreamKind, constraints: MatrixRtcVideoConstraints) {
        videoConstraints += MatrixRtcStreamRef(memberId, kind) to constraints
    }

    override fun hangUp() {
        hangUpCount++
    }
}
