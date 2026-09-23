/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallController
import io.element.android.call.api.ElementCallData
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.audio.CallAudioDevice
import io.element.android.call.api.rtc.MatrixRtcParticipant
import io.element.android.call.api.rtc.MatrixRtcScreenCaptureToken
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcStreamState
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.MatrixRtcVideoConstraints
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import io.element.android.call.test.ElementCallTestPattern
import io.element.android.call.ui.previewOwnTile
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import timber.log.Timber

/**
 * A controller with no call behind it, whose controls nevertheless do what they say.
 *
 * `FakeElementCallController` in `call/test` only records; a harness needs mute to mute, the camera to
 * come on, minimize to minimize and hang up to end, so a layout can be walked through by hand. The
 * snapshot is the whole truth here, edited in place, and the video is [ElementCallTestPattern] frames
 * through the same `videoFrames` lambda the product passes.
 */
class SampleElementCallController(
    scope: CoroutineScope,
) : ElementCallController {
    private val _state = MutableStateFlow<ElementCallSnapshot?>(null)
    override val state: StateFlow<ElementCallSnapshot?> = _state.asStateFlow()

    // The same rule as the real controller, so picture-in-picture behaves the same way from the sample.
    override val shouldEnterPictureInPicture: StateFlow<Boolean> = _state
        .map { it != null && (it.isMaximized || it.hasVideo) }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _isInPictureInPicture = MutableStateFlow(false)
    override val isInPictureInPicture: StateFlow<Boolean> = _isInPictureInPicture.asStateFlow()

    private val patterns = mutableMapOf<Pair<String, MatrixRtcStreamKind>, Flow<MatrixRtcVideoFrame>>()

    /** Open a fixture as the running call, replacing whatever was running. */
    fun start(snapshot: ElementCallSnapshot) {
        _state.value = snapshot
    }

    override fun setInPictureInPicture(isInPictureInPicture: Boolean) {
        _isInPictureInPicture.value = isInPictureInPicture
        if (!isInPictureInPicture && _state.value != null) setMaximized(true)
    }

    override fun startCall(callData: ElementCallData) {
        if (_state.value != null) return
        _state.value = ElementCallSnapshot(callData = callData, connection = ElementCallConnection.RequestingPermission)
    }

    override fun setMicrophonePermissionGranted(granted: Boolean) = update {
        it.copy(
            isMicrophonePermissionGranted = granted,
            connection = when {
                !granted -> ElementCallConnection.Failed("Microphone permission denied")
                it.connection == ElementCallConnection.RequestingPermission -> ElementCallConnection.Connected
                else -> it.connection
            },
        )
    }

    override fun setCameraPermissionGranted(granted: Boolean) {
        update { it.copy(isCameraPermissionGranted = granted) }
        if (granted) setCameraEnabled(true)
    }

    override fun setMicrophoneMuted(muted: Boolean) = update {
        it.copy(isMicrophoneMuted = muted, participants = it.participants.withLocalStream(MatrixRtcStreamKind.MICROPHONE, isMuted = muted))
    }

    override fun setCameraEnabled(enabled: Boolean) = update {
        it.copy(isCameraEnabled = enabled, participants = it.participants.withLocalStream(MatrixRtcStreamKind.CAMERA, isMuted = !enabled))
    }

    override fun switchCamera() = update { it.copy(isFrontCamera = !it.isFrontCamera) }

    override fun setAudioTestToneEnabled(enabled: Boolean) = update { it.copy(isAudioTestToneEnabled = enabled) }

    override fun selectAudioDevice(device: CallAudioDevice) = update { it.copy(selectedAudioDevice = device) }

    override fun setMaximized(maximized: Boolean) = update { it.copy(isMaximized = maximized) }

    override fun toggleTileStats() = update { it.copy(isTileStatsVisible = !it.isTileStatsVisible) }

    /**
     * One pattern per stream, stable for the life of the controller, as the real controller's flows are.
     * Shared screens are landscape; cameras alternate by member, so both aspects are always on screen.
     */
    override fun videoFrames(memberId: String, kind: MatrixRtcStreamKind): Flow<MatrixRtcVideoFrame> =
        patterns.getOrPut(memberId to kind) {
            val pattern = when {
                kind == MatrixRtcStreamKind.SCREEN_SHARE -> ElementCallTestPattern.landscape()
                memberId.hashCode() % 2 == 0 -> ElementCallTestPattern.portrait()
                else -> ElementCallTestPattern.landscape()
            }
            pattern.frames()
        }

    override fun setScreenShareEnabled(token: MatrixRtcScreenCaptureToken?) = update {
        it.copy(
            isScreenSharing = token != null,
            participants = it.participants.withLocalStream(MatrixRtcStreamKind.SCREEN_SHARE, isMuted = token == null),
        )
    }

    override fun setComposedTiles(tileIds: Set<MatrixRtcTileId>) {
        // Nothing to poll: the harness has no transport.
    }

    override fun setVideoConstraints(memberId: String, kind: MatrixRtcStreamKind, constraints: MatrixRtcVideoConstraints) {
        // Logged rather than acted on: there is no encoder to tell, but seeing the sizes tiles ask for is
        // exactly what the harness is for.
        Timber.d("Sample: $memberId/$kind wants $constraints")
    }

    override fun hangUp() {
        _state.value = null
    }

    private fun update(block: (ElementCallSnapshot) -> ElementCallSnapshot) {
        // Our own tile follows our own streams, as the core's local state does.
        _state.update { it?.let(block)?.let { snapshot -> snapshot.copy(ownTile = snapshot.participants.previewOwnTile()) } }
    }

    /** Our own participant with one stream set the given way, added if we were not publishing it. */
    private fun ImmutableList<MatrixRtcParticipant>.withLocalStream(kind: MatrixRtcStreamKind, isMuted: Boolean): ImmutableList<MatrixRtcParticipant> =
        map { participant ->
            if (!participant.isLocal) return@map participant
            val others = participant.streams.filterNot { it.kind == kind }
            participant.copy(streams = others + MatrixRtcStreamState(kind, isMuted = isMuted))
        }.toImmutableList()
}
