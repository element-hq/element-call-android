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
import io.element.android.call.api.ElementCallFailure
import io.element.android.call.api.ElementCallRoomMember
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.ElementCallWindowRect
import io.element.android.call.api.audio.CallAudioDevice
import io.element.android.call.api.rtc.MatrixRtcDetailWindow
import io.element.android.call.api.rtc.MatrixRtcScreenCaptureToken
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.MatrixRtcTileRoster
import io.element.android.call.api.rtc.MatrixRtcVideoConstraints
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import io.element.android.call.test.ElementCallTestPattern
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
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
    private val scope: CoroutineScope,
) : ElementCallController {
    private val _state = MutableStateFlow<ElementCallSnapshot?>(null)
    override val state: StateFlow<ElementCallSnapshot?> = _state.asStateFlow()

    // The same rule as the real controller, so picture-in-picture behaves the same way from the sample.
    override val shouldEnterPictureInPicture: StateFlow<Boolean> = _state
        .map { it != null && it.isMaximized }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _isInPictureInPicture = MutableStateFlow(false)
    override val isInPictureInPicture: StateFlow<Boolean> = _isInPictureInPicture.asStateFlow()

    private val patterns = mutableMapOf<Pair<String, MatrixRtcStreamKind>, Flow<MatrixRtcVideoFrame>>()

    /** Our camera is upright in the stage's orientation, as a real one is, so our own pattern follows it. */
    private val isStageLandscape = MutableStateFlow(false)

    fun setStageLandscape(isLandscape: Boolean) {
        isStageLandscape.value = isLandscape
    }

    /** Open a fixture as the running call, replacing whatever was running. */
    fun start(snapshot: ElementCallSnapshot) {
        _state.value = snapshot
    }

    override fun setInPictureInPicture(isInPictureInPicture: Boolean) {
        _isInPictureInPicture.value = isInPictureInPicture
        if (!isInPictureInPicture && _state.value != null) setMaximized(true)
    }

    private val _pictureInPictureSource = MutableStateFlow<ElementCallWindowRect?>(null)
    override val pictureInPictureSource: StateFlow<ElementCallWindowRect?> = _pictureInPictureSource.asStateFlow()

    // Frozen in the window, as the real controller does, so the sample's window keeps its shape.
    override fun setPictureInPictureSource(source: ElementCallWindowRect?) {
        if (!_isInPictureInPicture.value) _pictureInPictureSource.value = source
    }

    private val _pictureInPictureEntry = MutableStateFlow<ElementCallWindowRect?>(null)
    override val pictureInPictureEntry: StateFlow<ElementCallWindowRect?> = _pictureInPictureEntry.asStateFlow()

    override fun setPictureInPictureEntry(entry: ElementCallWindowRect?) {
        _pictureInPictureEntry.value = entry
    }

    override fun startCall(callData: ElementCallData) {
        if (_state.value != null) return
        _state.value = ElementCallSnapshot(callData = callData, connection = ElementCallConnection.RequestingPermission)
    }

    override fun setMicrophonePermissionGranted(granted: Boolean) = update {
        it.copy(
            isMicrophonePermissionGranted = granted,
            connection = when {
                !granted -> ElementCallConnection.Failed(ElementCallFailure.Other("Microphone permission denied"))
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
        it.copy(isMicrophoneMuted = muted, ownTile = it.ownTile?.copy(isMicrophoneMuted = muted))
    }

    override fun setCameraEnabled(enabled: Boolean) = update {
        it.copy(isCameraEnabled = enabled, ownTile = it.ownTile?.copy(hasVideo = enabled))
    }

    override fun switchCamera() = update { it.copy(isFrontCamera = !it.isFrontCamera) }

    override fun setAudioTestToneEnabled(enabled: Boolean) = update { it.copy(isAudioTestToneEnabled = enabled) }

    override fun selectAudioDevice(device: CallAudioDevice) = update { it.copy(selectedAudioDevice = device) }

    override fun setMaximized(maximized: Boolean) = update { it.copy(isMaximized = maximized) }

    override fun toggleTileStats() = update { it.copy(isTileStatsVisible = !it.isTileStatsVisible) }

    /**
     * One pattern per stream, stable for the life of the controller, as the real controller's flows are.
     * Bob's and Erin's cameras are portrait and everything else landscape, as on iOS (feature-hq
     * `harness/fixtures.md`), so both aspects are on the stage at once. Ours follows the stage.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun videoFrames(memberId: String, kind: MatrixRtcStreamKind): Flow<MatrixRtcVideoFrame> =
        patterns.getOrPut(memberId to kind) {
            when {
                kind == MatrixRtcStreamKind.CAMERA && memberId == OWN_MEMBER -> {
                    val portrait = ElementCallTestPattern.portrait()
                    val landscape = ElementCallTestPattern.landscape()
                    isStageLandscape.flatMapLatest { if (it) landscape.frames() else portrait.frames() }
                }
                kind == MatrixRtcStreamKind.CAMERA && memberId in PORTRAIT_MEMBERS -> ElementCallTestPattern.portrait().frames()
                else -> ElementCallTestPattern.landscape().frames()
            }
        }

    override fun setScreenShareEnabled(token: MatrixRtcScreenCaptureToken?) = update { it.copy(isScreenSharing = token != null) }

    override fun setComposedTiles(tileIds: Set<MatrixRtcTileId>) {
        // Nothing to poll: the harness has no transport.
    }

    /** The core's roster as a scenario states it: the order whole, detail as given. Names come from the user ids. */
    fun setRoster(roster: MatrixRtcTileRoster) {
        update { snapshot ->
            val members = roster.order.associate { ref ->
                ref.userId to ElementCallRoomMember(
                    ref.userId,
                    displayName = ref.userId.value.substringAfter('@').substringBefore(':').replaceFirstChar(Char::uppercase),
                    avatarUrl = null,
                )
            }
            val next = snapshot.copy(
                roster = roster,
                memberCount = roster.order.map { it.id.memberId }.distinct().size + 1,
                roomMembers = (snapshot.roomMembers + members).toImmutableMap(),
            )
            // Our hand stays last in the queue as the remote ones come and go.
            if (next.ownTile?.handRaisedAtMs != null) next.withOwnHand(raised = true) else next
        }
    }

    /** A scenario's `me ^`: what the core publishes, so no round trip. */
    fun setOwnHandRaised(raised: Boolean) = update { it.withOwnHand(raised) }

    fun currentRoster(): MatrixRtcTileRoster = _state.value?.roster ?: MatrixRtcTileRoster.EMPTY

    override fun setDetailWindow(window: MatrixRtcDetailWindow) {
        // The fixtures carry detail for everything; logged so the window a layout asks for can be read off.
        Timber.d("Sample: detail window $window")
    }

    override fun setRankingThreshold(tiles: Int) {
        // The fixtures list their rosters in the order the scenarios mean, which is already join order.
        Timber.d("Sample: ranking threshold $tiles")
    }

    override fun setVideoConstraints(memberId: String, kind: MatrixRtcStreamKind, constraints: MatrixRtcVideoConstraints) {
        // Logged rather than acted on: there is no encoder to tell, but seeing the sizes tiles ask for is
        // exactly what the harness is for.
        Timber.d("Sample: $memberId/$kind wants $constraints")
    }

    /** Shown at once, as the core shows a request on our tile. */
    override fun setHandRaised(raised: Boolean) {
        if (_state.value?.ownTile == null) return
        update { it.withOwnHand(raised) }
    }

    override fun clearHandRequestFailure() = update { it.copy(handRequestFailure = null) }

    override fun hangUp() {
        _state.value = null
    }

    /** Ours goes to the back of the queue; when it comes down, the hands after it move up one (009 R6). */
    private fun ElementCallSnapshot.withOwnHand(raised: Boolean): ElementCallSnapshot {
        val own = ownTile ?: return this
        val remotes = roster.detail.values
        val ownPosition = own.handRaisedPosition
        val newOwn = if (raised) {
            own.copy(handRaisedAtMs = 1L, handRaisedPosition = 1 + remotes.count { it.handRaisedPosition != null })
        } else {
            own.copy(handRaisedAtMs = null, handRaisedPosition = null)
        }
        val detail = if (!raised && ownPosition != null) {
            roster.detail.mapValues { (_, tile) ->
                val position = tile.handRaisedPosition
                if (position != null && position > ownPosition) tile.copy(handRaisedPosition = position - 1) else tile
            }
        } else {
            roster.detail
        }
        return copy(ownTile = newOwn, roster = roster.copy(detail = detail))
    }

    private fun update(block: (ElementCallSnapshot) -> ElementCallSnapshot) {
        _state.update { it?.let(block) }
    }

    private companion object {
        val PORTRAIT_MEMBERS = setOf(SampleFixture.memberIdOf("Bob"), SampleFixture.memberIdOf("Erin"))
        val OWN_MEMBER = SampleFixture.memberIdOf("Alice")
    }
}
