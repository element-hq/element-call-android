/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import android.content.Context
import io.element.android.call.api.ElementCallDispatchers
import io.element.android.call.api.rtc.MatrixRtcAudioLevel
import io.element.android.call.api.rtc.MatrixRtcCall
import io.element.android.call.api.rtc.MatrixRtcCallEvent
import io.element.android.call.api.rtc.MatrixRtcFrameEncryptionDiagnostic
import io.element.android.call.api.rtc.MatrixRtcFrameEncryptionState
import io.element.android.call.api.rtc.MatrixRtcLocalState
import io.element.android.call.api.rtc.MatrixRtcParticipant
import io.element.android.call.api.rtc.MatrixRtcReceiveStats
import io.element.android.call.api.rtc.MatrixRtcScreenCaptureToken
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcStreamRef
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.MatrixRtcTileRoster
import io.element.android.call.api.rtc.MatrixRtcVideoConstraints
import io.element.android.call.api.rtc.MatrixRtcVideoFrame
import io.element.android.call.impl.rtc.media.AudioCapture
import io.element.android.call.impl.rtc.media.AudioFormat
import io.element.android.call.impl.rtc.media.AudioPlayback
import io.element.android.call.impl.rtc.media.CameraVideoCapture
import io.element.android.call.impl.rtc.media.ScreenVideoCapture
import io.element.android.call.impl.rtc.media.VideoFormat
import io.element.android.call.impl.util.runCatchingExceptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.rtc.FfiAudioSourceConfig
import org.matrix.rtc.FfiLocalTrack
import org.matrix.rtc.FfiMediaConstraints
import org.matrix.rtc.FfiPublishOptions
import org.matrix.rtc.FfiStreamKind
import org.matrix.rtc.FfiVideoDetail
import org.matrix.rtc.FfiVideoSourceConfig
import org.matrix.rtc.MediaSession
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

internal class RustMatrixRtcCall(
    override val localMemberId: String,
    private val mediaSession: MediaSession,
    private val callScope: CoroutineScope,
    private val ffiDispatcher: CoroutineDispatcher,
    /** Needed to open the camera, which is the only part of media that takes one. */
    private val context: Context,
    /**
     * For the remote video flows, which are collected by the UI: without moving them off the
     * collector's thread every decoded frame would be copied on the main one, thirty times a second.
     */
    private val dispatchers: ElementCallDispatchers,
) : MatrixRtcCall {
    private val _events = MutableSharedFlow<MatrixRtcCallEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<MatrixRtcCallEvent> = _events

    private val _participants = MutableStateFlow(emptyList<MatrixRtcParticipant>())
    override val participants: StateFlow<List<MatrixRtcParticipant>> = _participants

    private val _isMicrophoneMuted = MutableStateFlow(false)
    override val isMicrophoneMuted: StateFlow<Boolean> = _isMicrophoneMuted

    private val _isAudioTestToneEnabled = MutableStateFlow(false)
    override val isAudioTestToneEnabled: StateFlow<Boolean> = _isAudioTestToneEnabled

    private val _isCameraEnabled = MutableStateFlow(false)
    override val isCameraEnabled: StateFlow<Boolean> = _isCameraEnabled

    private val _isFrontCamera = MutableStateFlow(true)
    override val isFrontCamera: StateFlow<Boolean> = _isFrontCamera

    private val _tiles = MutableStateFlow(MatrixRtcTileRoster.EMPTY)
    override val tiles: StateFlow<MatrixRtcTileRoster> = _tiles

    private val _localState = MutableStateFlow<MatrixRtcLocalState?>(null)
    override val localState: StateFlow<MatrixRtcLocalState?> = _localState

    // From the publication rather than from what we asked for, so a share that ends any other way
    // still reads false. Keeping it true is on us: an OS-level stop has to become an unpublish.
    override val isScreenSharing: StateFlow<Boolean> = _localState
        .map { it?.isScreenSharing == true }
        .stateIn(callScope, SharingStarted.Eagerly, false)

    // What we last asked for, so a second stop - ours racing the projection's own - is a no-op.
    private var isCapturingScreen = false

    /**
     * One frame of slack and drop the oldest, because the camera must never wait for a renderer. A
     * dropped frame costs a stale self view for a thirtieth of a second; blocking here would stall
     * the camera thread and, with it, publishing to the call.
     */
    private val localVideoFrames = MutableSharedFlow<MatrixRtcVideoFrame>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * The flow object for one of a member's video streams, built once. Cheap to keep - a cold flow
     * holds nothing until it is collected - and the identity matters: a Compose effect keyed on the
     * flow would restart, and so reopen the video stream, every time a caller asked again.
     *
     * Keyed by member *and* kind, because a member sharing their screen while their camera is on has
     * two independent video streams. Keyed by member alone, as this was, the second one to be asked
     * for would silently get the first one's frames.
     */
    private val remoteVideoFlows = ConcurrentHashMap<MatrixRtcStreamRef, Flow<MatrixRtcVideoFrame>>()

    /**
     * Our own camera frames come from capture, which is already running and already shared.
     * Everything else is decoded on demand: opening the stream is what makes the core decode at all,
     * so a stream nobody draws costs nothing.
     *
     * The same member and kind always get the same flow instance, so a caller may ask as often as it
     * likes.
     *
     * Our own *screen* is not special-cased and goes down the remote path: we publish it but keep no
     * local copy, because the one person who does not need to be shown their screen is the person
     * sharing it.
     */
    override fun videoFrames(memberId: String, kind: MatrixRtcStreamKind): Flow<MatrixRtcVideoFrame> =
        if (memberId == localMemberId && kind == MatrixRtcStreamKind.CAMERA) {
            localVideoFrames
        } else {
            remoteVideoFlows.getOrPut(MatrixRtcStreamRef(memberId, kind)) { remoteVideoFrames(memberId, kind) }
        }

    private fun remoteVideoFrames(memberId: String, kind: MatrixRtcStreamKind): Flow<MatrixRtcVideoFrame> = flow {
        val stream = withContext(ffiDispatcher) {
            runCatchingExceptions { mediaSession.videoStream(memberId, kind.map()) }
                .onFailure { Timber.w(it, "MatrixRTC: cannot open $kind video stream for $memberId") }
                .getOrNull()
        } ?: return@flow

        // try/finally rather than `use`, which uniffi also defines on its own Disposable and which
        // resolves ambiguously here. The finally is the point either way: cancelling the collector -
        // a tile leaving the screen - has to close the stream, or the core keeps decoding for nobody.
        try {
            var frameCount = 0L
            while (true) {
                val frame = runCatchingExceptions { stream.next() }
                    .onFailure { Timber.w(it, "MatrixRTC: $kind video stream for $memberId ended") }
                    .getOrNull()
                    ?: break
                // Wrapped where it lies rather than copied out, so the ref stays alive until the
                // frame is released - see `mapZeroCopy`. Nothing closes the ref here any more: doing
                // so would free the very memory being handed downstream.
                val mapped = frame.mapZeroCopy()
                frameCount++
                if (frameCount == 1L || frameCount % FRAMES_PER_VIDEO_LOG == 0L) {
                    Timber.i("MatrixRTC: decoded $frameCount $kind frame(s) from $memberId, ${mapped.width}x${mapped.height}")
                }
                emit(mapped)
            }
        } finally {
            stream.close()
        }
    }
        // `stream.next()` blocks, so the whole producer above belongs off the collector's thread.
        .flowOn(dispatchers.io)
        // Conflated for the same reason capture drops frames: a renderer that falls behind must not
        // become backpressure on the decoder.
        //
        // A Channel rather than `buffer(...)`, and that is not a style choice. A dropped frame is
        // never collected, so nothing downstream can release it - and now that a frame owns native
        // memory, every drop would leak most of a megabyte. Dropping is the *normal* case for a
        // renderer that has fallen behind, so this would be a leak under exactly the conditions that
        // cause it. `buffer(...)` has no undelivered-element hook; `Channel` does, which is the only
        // reason this is spelled out by hand.
        //
        // **The order of these two lines is load-bearing.** Conflation releases each frame as soon as
        // `emit` returns, so nothing downstream of it may buffer: `flowOn` after this put a 64-deep
        // channel between the release and the renderer, so every frame was freed while still queued
        // and the renderer got a dead frame it had to refuse. The core kept decoding at 30fps and
        // about one frame in a hundred reached the screen. Conflating last means `emit` returns only
        // once a subscriber has actually taken the frame.
        .conflateReleasingDropped()

    private val _audioLevels = MutableStateFlow<Map<String, MatrixRtcAudioLevel>>(emptyMap())
    override val audioLevels: StateFlow<Map<String, MatrixRtcAudioLevel>> = _audioLevels

    private val _receiveStats = MutableStateFlow<Map<MatrixRtcStreamRef, MatrixRtcReceiveStats>>(emptyMap())
    override val receiveStats: StateFlow<Map<MatrixRtcStreamRef, MatrixRtcReceiveStats>> = _receiveStats

    /** Last logged frame-encryption state per member, so only transitions are logged. Event pump only. */
    private val lastFrameEncryption = mutableMapOf<String, MatrixRtcFrameEncryptionState>()

    /** Streams the core has answered with no counters, so the "no stats yet" line is said once. */
    private val streamsWithoutStats = mutableSetOf<MatrixRtcStreamRef>()

    /** What the screen composes, see [setComposedTiles]; the stats poll asks about nothing else. */
    private val composedTiles = MutableStateFlow<Set<MatrixRtcTileId>>(emptySet())

    /**
     * Members already reported as publishing no microphone, so the warning appears once each. Cleared
     * per member when one appears, so a stream arriving late and then going away is reported twice
     * rather than being swallowed as already-said.
     */
    private val membersWithoutMicrophone = mutableSetOf<String>()

    /**
     * Members whose playback we have taken on. The sweep in [start] and a `StreamStarted` event race
     * for anyone already publishing when we connect, and both would otherwise open a stream.
     */
    private val playbackClaims = ConcurrentHashMap.newKeySet<String>()

    private val audioCapture = AudioCapture(callScope, onLevel = { publishAudioLevel(localMemberId, it) })
    private val audioPlayback = AudioPlayback(callScope, onLevel = ::publishAudioLevel)
    private var microphoneTrack: FfiLocalTrack? = null

    private val videoCapture = CameraVideoCapture(context, onFrame = { localVideoFrames.tryEmit(it) })

    /**
     * Published on the first [setCameraEnabled] and kept for the rest of the call, even while the
     * camera is off. Republishing would renegotiate, and a peer would see the track disappear and
     * come back rather than simply mute.
     */
    private var cameraTrack: FfiLocalTrack? = null

    private val screenCapture = ScreenVideoCapture(context)

    /**
     * Published for the duration of one share and retracted when it stops, unlike [cameraTrack].
     *
     * A screen has no "off" for a publication to represent while idle - see [unpublishScreenShare].
     */
    private var screenTrack: FfiLocalTrack? = null

    // The meters run on the capture and playback reader threads, so both sides use update().
    private fun publishAudioLevel(memberId: String, level: MatrixRtcAudioLevel) {
        _audioLevels.update { it + (memberId to level) }
    }

    private fun stopPlayback(memberId: String) {
        audioPlayback.stop(memberId)
        playbackClaims.remove(memberId)
        _audioLevels.update { it - memberId }
        _receiveStats.update { stats -> stats.filterKeys { it.memberId != memberId } }
        // A member who left will not come back under this id - the core mints a fresh one on rejoin -
        // so their cached flows are dead weight from here on. All of them: a member who was sharing
        // their screen as well as their camera has one per kind.
        remoteVideoFlows.keys.removeAll { it.memberId == memberId }
    }

    fun start() {
        pumpEvents()
        pumpTiles()
        pumpParticipants()
        followPlayback()
        pollReceiveStats()
    }

    /**
     * The participant roster, pushed by the core with the latest value winning - so there is nothing
     * to re-read after an event, and a lagging event consumer can never leave it stale.
     */
    private fun pumpParticipants() {
        callScope.launch {
            pump("participants", seed = { mediaSession.participants() }, next = { mediaSession.nextParticipants() }) { participants ->
                val refreshed = participants.map { it.map() }
                // Logged when the set changes, because "why do I see a member who left" needs to name
                // the layer holding the extra row: this is the media roster, the core's own membership
                // projection shows up as its `membership changed` line.
                if (refreshed.mapTo(mutableSetOf()) { it.memberId } != _participants.value.mapTo(mutableSetOf()) { it.memberId }) {
                    Timber.i("MatrixRTC: media roster ${refreshed.size}: ${refreshed.map { "${it.memberId}${if (it.isLocal) " (self)" else ""}" }}")
                }
                _participants.value = refreshed
            }
        }
    }

    /**
     * Who we play follows the roster, not the event stream: a remote member with a microphone stream
     * has a player, a member without one does not. Driven from a latest-value push, so a missed
     * `StreamStarted` can never leave someone silent and a missed `ParticipantLeft` can never leak
     * their player.
     */
    private fun followPlayback() {
        callScope.launch {
            _participants
                .map { microphoneMembers(it, localMemberId) }
                .distinctUntilChanged()
                .collect { wanted ->
                    (playbackClaims - wanted).forEach { stopPlayback(it) }
                    (wanted - playbackClaims).forEach { playAudioOf(it) }
                }
        }
    }

    /**
     * The single consumer of `nextRoster()` and `nextLocalState()`, seeded from their pulls first so
     * the stage has everyone from its first frame. Both long-poll and return the latest value, so a
     * slow collector skips superseded rosters rather than queueing them.
     */
    private fun pumpTiles() {
        callScope.launch {
            pump("tile roster", seed = { mediaSession.roster() }, next = { mediaSession.nextRoster() }) { roster ->
                val mapped = roster.map()
                if (mapped.order != _tiles.value.order) {
                    Timber.d("MatrixRTC: tile order ${mapped.order.map { "${it.id.memberId}/${it.id.kind}${if (it.isHero) " (hero)" else ""}" }}")
                }
                _tiles.value = mapped
            }
        }
        callScope.launch {
            pump("local state", seed = { mediaSession.localState() }, next = { mediaSession.nextLocalState() }) {
                _localState.value = it.map()
            }
        }
    }

    private suspend fun <T : Any> pump(name: String, seed: () -> T?, next: suspend () -> T?, apply: (T) -> Unit) {
        withContext(ffiDispatcher) { runCatchingExceptions { seed() } }.getOrNull()?.let(apply)
        while (currentCoroutineContext().isActive) {
            val value = withContext(ffiDispatcher) { runCatchingExceptions { next() } }
                .onFailure { Timber.w(it, "MatrixRTC: $name pump stopped") }
                .getOrNull()
                ?: break
            apply(value)
        }
    }

    /**
     * The single consumer of `nextEvent()`. It suspends until the core has something, so it owns
     * the loop and everything else reacts to what it emits.
     */
    private fun pumpEvents() {
        callScope.launch {
            while (isActive) {
                // On the FFI dispatcher like every other call into the session: nextEvent suspends
                // rather than blocking, so it does not hold the thread while waiting.
                val event = withContext(ffiDispatcher) {
                    runCatchingExceptions { mediaSession.nextEvent() }
                }
                    .onFailure { Timber.w(it, "MatrixRTC: media event pump stopped") }
                    .getOrNull()
                    ?: break

                val mapped = event.map()
                if (mapped != null) {
                    handleInternally(mapped)
                    _events.emit(mapped)
                } else {
                    Timber.d("MatrixRTC: ignoring ${event::class.simpleName}, not carried by this library yet")
                }
            }
        }
    }

    override fun setComposedTiles(tileIds: Set<MatrixRtcTileId>) {
        composedTiles.value = tileIds
    }

    /**
     * The counters come from RTCP, which reports about once a second, so polling faster would only
     * repeat values. One batched call a second for the streams of the tiles the screen composes, plus
     * their members' microphones - never the whole roster, which at two hundred members was two
     * round trips per member per second on this one lane. Our own streams have nothing to receive.
     */
    private fun pollReceiveStats() {
        callScope.launch {
            while (isActive) {
                delay(STATS_POLL_INTERVAL)
                pollReceiveStatsOnce()
            }
        }
    }

    private suspend fun pollReceiveStatsOnce() {
        val remoteMembers = _participants.value.filterNot { it.isLocal }
        val withMicrophone = remoteMembers
            .filter { participant -> participant.streams.any { it.kind == MatrixRtcStreamKind.MICROPHONE } }
            .map { it.memberId }
        logMembersWithoutMicrophone(remoteMembers, withMicrophone)
        val streams = streamsToPoll(composedTiles.value.filterNot { it.memberId == localMemberId }, withMicrophone.toSet())
        if (streams.isEmpty()) {
            _receiveStats.value = emptyMap()
            streamsWithoutStats.clear()
            return
        }
        val answered = withContext(ffiDispatcher) {
            runCatchingExceptions { mediaSession.receiveStatsFor(streams.map { it.map() }) }
                .onFailure { Timber.w(it, "MatrixRTC: cannot read receive stats") }
                .getOrNull()
        } ?: return
        // Null until the first RTCP report lands, which is not the same as zero.
        val stats = answered.associate { it.map() }
        _receiveStats.value = stats.mapNotNull { (stream, streamStats) -> streamStats?.let { stream to it } }.toMap()
        logReceiveStats(stats)
    }

    /**
     * A remote member with no microphone stream at all, said once per member and at warn.
     *
     * Nothing used to report this, and it is not a state anything else makes visible: playback is
     * never opened, so there is no `audio in` line and no `rx audio` line either, and the tile draws
     * the same mute badge it draws for someone who muted themselves. The absence looked exactly like
     * a quiet participant. It took a side-by-side with Element Call - which had the same member
     * unmuted and audible to everyone else - to find, which is a diagnosis that should not have
     * needed a second client.
     *
     * At warn rather than info because it is one-sided in the way that hides it: our end is healthy,
     * we simply never get their audio, and the far end has no idea we cannot hear them.
     */
    private fun logMembersWithoutMicrophone(remoteMembers: List<MatrixRtcParticipant>, withMicrophone: List<String>) {
        remoteMembers.forEach { participant ->
            if (participant.memberId in withMicrophone) {
                membersWithoutMicrophone.remove(participant.memberId)
                return@forEach
            }
            if (membersWithoutMicrophone.add(participant.memberId)) {
                val kinds = participant.streams.joinToString { it.kind.name }.ifEmpty { "none" }
                Timber.w(
                    "MatrixRTC: ${participant.memberId} publishes no microphone stream, so they cannot be heard " +
                        "here and are drawn as muted - streams: $kinds"
                )
            }
        }
    }

    /**
     * On logcat at info, because these counters are what tell a starved stream apart from a silent
     * one and a pasted log is often all we have to go on. Once a second per composed stream, which is
     * nothing next to the SDK's own output.
     *
     * A stream the core has no stats for is reported too, once: otherwise an absent `rx` line reads
     * the same whether there was nothing to poll or every read came back null.
     */
    private fun logReceiveStats(stats: Map<MatrixRtcStreamRef, MatrixRtcReceiveStats?>) {
        stats.forEach { (stream, streamStats) ->
            if (streamStats == null) {
                if (streamsWithoutStats.add(stream)) {
                    Timber.i("MatrixRTC: no receive stats yet for ${stream.memberId}/${stream.kind}")
                }
                return@forEach
            }
            streamsWithoutStats.remove(stream)
            if (stream.kind == MatrixRtcStreamKind.MICROPHONE) {
                // No frame counters here on purpose: an audio stream has no frames to decode, and
                // logging them would only ever print zero and read as "no video is arriving".
                val invented = streamStats.concealedFraction?.let { "${(it * 100).roundToInt()}%" } ?: "unknown"
                Timber.i(
                    "MatrixRTC: rx audio ${stream.memberId} - ${streamStats.packetsReceived} pkts, ${streamStats.packetsLost} lost, " +
                        "$invented invented, jitter ${"%.3f".format(streamStats.jitter)}s"
                )
            } else {
                // The pair that tells a decode stall from a delivery one when a tile goes black:
                // frames flat while packets climb is the decoder stuck on what arrived; both climbing
                // with nothing drawn puts the loss between the core and us.
                Timber.i(
                    "MatrixRTC: rx video ${stream.memberId}/${stream.kind} - ${streamStats.packetsReceived} pkts, " +
                        "${streamStats.packetsLost} lost, ${streamStats.framesDecoded} decoded, ${streamStats.framesDropped} dropped"
                )
            }
        }
    }

    private suspend fun handleInternally(event: MatrixRtcCallEvent) {
        when (event) {
            // Both directions of the transition are logged, and at warn: the difference between a
            // quiet call and a broken one has to survive the log level being turned down, and a
            // failure that clears on its own is a rotation delay rather than a bug - which is only
            // visible if the recovery is logged too. Only changes, since the core can repeat a state.
            is MatrixRtcCallEvent.FrameEncryption -> {
                val previous = lastFrameEncryption.put(event.memberId, event.state)
                if (previous != event.state) {
                    // The diagnostic is what splits a MISSING_KEY in two: nothing was ever installed
                    // for this member, or frames are arriving stamped with an index we have not been
                    // given. Those need entirely different investigations and read identically
                    // without it.
                    Timber.w(
                        "MatrixRTC: frame encryption ${event.state} for ${event.memberId} " +
                            "(was ${previous ?: "unknown"}, ${event.diagnostic.describe()})"
                    )
                }
            }
            // A refused key and a key that never arrived both leave the member at MISSING_KEY, and
            // the reason used to stay inside the core. NotCrossSigned in particular is something the
            // user can act on rather than a fault.
            is MatrixRtcCallEvent.KeyDiscarded ->
                Timber.w(
                    "MatrixRTC: key index ${event.keyIndex} for ${event.memberId} discarded, " +
                        "${event.reason} (from ${event.senderUserId}/${event.senderDeviceId})"
                )
            // The only report of what the media layer actually installed, and the one thing that
            // separates the three ways a member can sit at MISSING_KEY: we never fed the key, we fed
            // it and the media layer refused it, or it was installed at an index the frame cryptor is
            // not asking for. Paired with the fed-key line, which carries the same index, and with our
            // own member id here, which needs no network at all to go wrong.
            is MatrixRtcCallEvent.KeyImported ->
                Timber.i("MatrixRTC: key index ${event.keyIndex} imported for ${event.memberId}")
            is MatrixRtcCallEvent.Ended -> {
                audioPlayback.stopAll()
                playbackClaims.clear()
            }
            else -> Unit
        }
    }

    private suspend fun playAudioOf(memberId: String) {
        // Claiming before opening the stream, not after: two callers that both got past a check on
        // the playback map would each end up with a reader and an AudioTrack on the same member,
        // playing their audio twice and slightly out of step.
        if (!playbackClaims.add(memberId)) return
        val stream = withContext(ffiDispatcher) {
            runCatchingExceptions { mediaSession.audioStream(memberId, FfiStreamKind.MICROPHONE) }
                .onFailure { Timber.w(it, "MatrixRTC: cannot open audio stream for $memberId") }
                .getOrNull()
        }
        if (stream == null) {
            // Released so a later StreamStarted for this member can try again.
            playbackClaims.remove(memberId)
            return
        }
        audioPlayback.start(memberId, stream)
    }

    override suspend fun setVideoConstraints(
        memberId: String,
        kind: MatrixRtcStreamKind,
        constraints: MatrixRtcVideoConstraints,
    ): Result<Unit> = runCatchingExceptions {
        // Skipped when nothing changed, because a tile's size is recomputed on every layout pass and
        // most of those land on the same numbers. The FFI call is cheap but it reaches the SFU.
        val key = MatrixRtcStreamRef(memberId, kind)
        if (appliedConstraints[key] == constraints) return@runCatchingExceptions
        appliedConstraints[key] = constraints

        withContext(ffiDispatcher) {
            mediaSession.setConstraints(
                memberId,
                kind.map(),
                FfiMediaConstraints(
                    // Still subscribed either way: `visible` is what tells the SFU it may stop
                    // sending, and unsubscribing entirely is what closing the stream does.
                    enabled = true,
                    visible = constraints.isVisible,
                    // Dimensions rather than a quality band. The layout knows the exact size it is
                    // drawing at, and a band would be our guess about that same number.
                    detail = if (constraints.isVisible) {
                        FfiVideoDetail.Dimensions(constraints.widthPx.toUInt(), constraints.heightPx.toUInt())
                    } else {
                        FfiVideoDetail.Auto
                    },
                    lowBandwidth = false,
                )
            )
        }
        Timber.i(
            "MatrixRTC: constraints for $memberId $kind - " +
                if (constraints.isVisible) "${constraints.widthPx}x${constraints.heightPx}" else "not visible"
        )
    }.onFailure {
        Timber.w(it, "MatrixRTC: could not set video constraints for $memberId")
    }

    /** Last constraints actually sent per stream, so unchanged ones are not sent again. */
    private val appliedConstraints = ConcurrentHashMap<MatrixRtcStreamRef, MatrixRtcVideoConstraints>()

    override suspend fun publishMicrophone(muted: Boolean): Result<Unit> = runCatchingExceptions {
        if (microphoneTrack != null) return@runCatchingExceptions
        val track = withContext(ffiDispatcher) {
            mediaSession.publish(
                FfiPublishOptions(
                    kind = FfiStreamKind.MICROPHONE,
                    audio = FfiAudioSourceConfig(
                        sampleRate = AudioFormat.SAMPLE_RATE.toUInt(),
                        numChannels = AudioFormat.CHANNEL_COUNT.toUInt(),
                    ),
                    video = null,
                    simulcast = false,
                    // Published in the state the user is already in - the microphone button is on
                    // screen while the call connects - so no peer sees this member unmuted for the
                    // moment between the publication and the replay below.
                    muted = muted,
                )
            )
        }
        microphoneTrack = track
        audioCapture.setTestToneEnabled(_isAudioTestToneEnabled.value)
        audioCapture.start(track)
        // Replayed onto the freshly published track: the publication above carried the mute to the
        // transport, this is what carries it to the capturer and to isMicrophoneMuted.
        setMicrophoneMuted(muted)
        Timber.d("MatrixRTC: publishing microphone as $localMemberId")
    }.onFailure {
        Timber.w(it, "MatrixRTC: failed to publish microphone")
    }

    override suspend fun setMicrophoneMuted(muted: Boolean) {
        _isMicrophoneMuted.value = muted
        audioCapture.setMuted(muted)
        // Both ends of the mute, deliberately. Stopping capture is what saves the bandwidth, but on
        // its own it is indistinguishable to a peer from a client that has wedged and stopped
        // pushing frames - so the transport is told as well, and our own roster entry picks up the
        // muted stream the same way everyone else's does.
        withContext(ffiDispatcher) {
            runCatchingExceptions { mediaSession.setLocalMuted(FfiStreamKind.MICROPHONE, muted) }
                .onFailure { Timber.w(it, "MatrixRTC: could not tell the transport we are ${if (muted) "muted" else "unmuted"}") }
        }
    }

    override suspend fun setCameraEnabled(enabled: Boolean): Result<Unit> = runCatchingExceptions {
        if (_isCameraEnabled.value == enabled) return@runCatchingExceptions

        if (enabled) {
            val track = cameraTrack ?: withContext(ffiDispatcher) {
                mediaSession.publish(
                    FfiPublishOptions(
                        kind = FfiStreamKind.CAMERA,
                        audio = null,
                        video = FfiVideoSourceConfig(
                            width = VideoFormat.CAPTURE_WIDTH.toUInt(),
                            height = VideoFormat.CAPTURE_HEIGHT.toUInt(),
                        ),
                        // Not optional, whatever it looks like. LiveKit's dynacast pauses any
                        // encoding the SFU reports nobody subscribed to, and a peer rendering us in
                        // a small tile asks for the *low* layer. With one full-resolution encoding
                        // the only layer we produce is the only one never asked for, so it is paused
                        // and no video leaves the device - while capture, the self view and every log
                        // line on this side stay perfectly healthy. The far end simply shows grey and
                        // never creates an inbound stream at all.
                        //
                        // The evidence, from the core's own log:
                        //   dynacast: SFU quality update: subscribed_codecs="vp8:[Low=true, Medium=false, High=false]"
                        //
                        // There is no way to turn dynacast off from here - FfiPublishOptions offers
                        // this flag and nothing else - so publishing several layers is the only
                        // defence. See item 13 in `libraries/rustrtc/FEEDBACK.md`.
                        simulcast = true,
                        // Unmuted by setTransportMuted below, once capture is running: a peer told
                        // to expect frames before there are any is shown a grey tile meanwhile.
                        muted = true,
                    )
                )
            }.also { cameraTrack = it }
            videoCapture.start(track)
            // Read back rather than assumed: a device with no front camera starts on the back one.
            _isFrontCamera.value = videoCapture.isFrontFacing
            // Both ends, for the same reason as the microphone: unmuting the transport is what tells
            // a peer to expect frames again, and starting capture is what produces them.
            setTransportMuted(muted = false)
        } else {
            // The transport first. Capture stops within a frame or two either way, but a peer that
            // learns about it afterwards has already been shown a frozen picture.
            setTransportMuted(muted = true)
            videoCapture.stop()
        }
        _isCameraEnabled.value = enabled
        Timber.i("MatrixRTC: camera ${if (enabled) "enabled" else "disabled"} for $localMemberId")
    }.onFailure {
        Timber.w(it, "MatrixRTC: failed to ${if (enabled) "enable" else "disable"} the camera")
    }

    private suspend fun setTransportMuted(muted: Boolean) {
        withContext(ffiDispatcher) {
            runCatchingExceptions { mediaSession.setLocalMuted(FfiStreamKind.CAMERA, muted) }
                .onFailure { Timber.w(it, "MatrixRTC: could not tell the transport the camera is ${if (muted) "off" else "on"}") }
        }
    }

    override suspend fun setScreenShareEnabled(enabled: Boolean, token: MatrixRtcScreenCaptureToken?): Result<Unit> =
        runCatchingExceptions {
            if (isCapturingScreen == enabled) return@runCatchingExceptions

            if (enabled) {
                val resultData = token?.resultData
                if (resultData == null) {
                    Timber.w("MatrixRTC: cannot share the screen without the user's grant")
                    return@runCatchingExceptions
                }
                // Always a fresh publication: stopping unpublishes, which kills the handle, so unlike
                // the camera there is never a track left over to reuse. The elvis is a guard against
                // an enable that failed after publishing, not a reuse path.
                val track = screenTrack ?: withContext(ffiDispatcher) {
                    mediaSession.publish(
                        FfiPublishOptions(
                            kind = FfiStreamKind.SCREEN_SHARE,
                            audio = null,
                            video = FfiVideoSourceConfig(
                                width = VideoFormat.SCREEN_MAX_EDGE.toUInt(),
                                height = VideoFormat.SCREEN_MAX_EDGE.toUInt(),
                            ),
                            // For the same dynacast reason as the camera - see setCameraEnabled and
                            // item 13 in `libraries/rustrtc/FEEDBACK.md`. Arguably wrong for a screen,
                            // where one sharp layer beats three soft ones and text is the whole point,
                            // but a single layer is the one dynacast pauses, and shipping something
                            // that silently sends nothing is worse than shipping something soft.
                            simulcast = true,
                            // Unmuted by setScreenShareMuted below, once the projection has actually
                            // started - the reason that call is ordered after screenCapture.start.
                            muted = true,
                        )
                    )
                }.also { screenTrack = it }

                // Capture before unmuting: the projection can still be refused here - the token is
                // spent, the foreground service may not have the right type - and a peer told to
                // expect a screen that never arrives is shown a grey rectangle indefinitely.
                val started = screenCapture.start(
                    track = track,
                    permissionData = resultData,
                    // The user can end the share from the cast notification, which no part of the
                    // capturer interface reports. Without this the button would go on saying "stop
                    // sharing" for a share that had already stopped.
                    onStopped = { callScope.launch { setScreenShareEnabled(enabled = false) } },
                )
                if (!started) {
                    // Retracted rather than left muted: nothing will ever capture into this
                    // publication, and a peer that can see it draws a tile for it.
                    unpublishScreenShare()
                    return@runCatchingExceptions
                }
                setScreenShareMuted(muted = false)
            } else {
                // Capture first here, unlike the camera, which mutes the transport first so a peer is
                // never shown a frozen picture. Unpublishing is not a state a peer can be shown - the
                // stream goes away - so there is nothing to order against, and stopping capture first
                // is what guarantees no frame reaches a track that is being torn down.
                // `stopCapture()` blocks until the capture thread is done.
                // Latched first: stopping fires the projection's own onStop, which comes back here.
                isCapturingScreen = false
                screenCapture.stop()
                unpublishScreenShare()
            }
            if (enabled) isCapturingScreen = true
            Timber.i("MatrixRTC: screen share ${if (enabled) "started" else "stopped"} for $localMemberId")
        }.onFailure {
            Timber.w(it, "MatrixRTC: failed to ${if (enabled) "start" else "stop"} the screen share")
        }

    /**
     * Retract the screen-share publication, so receivers drop the stream rather than keep an empty
     * tile for a share that ended.
     *
     * The [FfiLocalTrack] `publish` handed us is dead once this returns - `captureVideo` on it fails
     * with a transport error - so the handle is dropped whatever happens, a failed unpublish included:
     * a stale handle would capture into nothing at all, while a fresh publish on the next share costs
     * only a renegotiation.
     */
    private suspend fun unpublishScreenShare() {
        withContext(ffiDispatcher) {
            runCatchingExceptions { mediaSession.unpublish(FfiStreamKind.SCREEN_SHARE) }
                .onFailure { Timber.w(it, "MatrixRTC: could not unpublish the screen share") }
        }
        screenTrack = null
    }

    /**
     * Only ever called to *unmute*, on a publication that is one call old. A stopped share is
     * unpublished instead, see [unpublishScreenShare]; the parameter is kept so the pair reads like
     * the camera's, whose track really does outlive its capture.
     */
    private suspend fun setScreenShareMuted(muted: Boolean) {
        withContext(ffiDispatcher) {
            runCatchingExceptions { mediaSession.setLocalMuted(FfiStreamKind.SCREEN_SHARE, muted) }
                .onFailure { Timber.w(it, "MatrixRTC: could not tell the transport the screen share is ${if (muted) "off" else "on"}") }
        }
    }

    override suspend fun switchCamera(): Result<Unit> = runCatchingExceptions {
        // Asynchronous by nature - the camera has to close and another open - so the state lands in
        // the callback rather than when this returns.
        videoCapture.switchCamera { isFrontCamera -> _isFrontCamera.value = isFrontCamera }
    }.onFailure {
        Timber.w(it, "MatrixRTC: failed to switch camera")
    }

    override fun setAudioTestToneEnabled(enabled: Boolean) {
        _isAudioTestToneEnabled.value = enabled
        audioCapture.setTestToneEnabled(enabled)
        Timber.i("MatrixRTC: audio test tone enabled=$enabled")
    }

    override suspend fun disconnect(): Result<Unit> = runCatchingExceptions {
        audioCapture.stop()
        videoCapture.stop()
        screenCapture.stop()
        audioPlayback.stopAll()
        withContext(ffiDispatcher) { mediaSession.disconnect() }
    }.onFailure {
        Timber.w(it, "MatrixRTC: failed to disconnect media session")
    }

    override fun close() {
        audioCapture.stop()
        // Before the scope is cancelled, and not left to the GC: this is what closes the camera
        // device, and a call torn down from recents would otherwise hold it - light on - until the
        // process died.
        videoCapture.stop()
        // Same reasoning, and more visible: an abandoned MediaProjection leaves the system's screen
        // recording indicator up for a call that has ended.
        screenCapture.stop()
        audioPlayback.stopAll()
        callScope.cancel()
        microphoneTrack = null
        cameraTrack = null
        screenTrack = null
    }

    private companion object {
        /** RTCP reports arrive about once a second, so a faster poll would only repeat itself. */
        val STATS_POLL_INTERVAL = 1.seconds

        /** Roughly five seconds of a 30 fps stream, matching the capture and audio meter cadence. */
        const val FRAMES_PER_VIDEO_LOG = 150L
    }
}

private fun MatrixRtcFrameEncryptionDiagnostic.describe(): String = when (this) {
    MatrixRtcFrameEncryptionDiagnostic.NotApplicable -> "nothing to explain"
    MatrixRtcFrameEncryptionDiagnostic.NoKeyInstalled -> "no key installed"
    is MatrixRtcFrameEncryptionDiagnostic.KeysInstalled -> "keys installed at $keyIndices"
}

/**
 * The streams one stats sample asks about: each composed tile's own stream, then one microphone per
 * member among them who publishes one. In that order, without repeats, so the answer reads as the
 * screen does.
 */
internal fun streamsToPoll(composed: Collection<MatrixRtcTileId>, withMicrophone: Set<String>): List<MatrixRtcStreamRef> {
    val tiles = composed.map { MatrixRtcStreamRef(it.memberId, it.kind.videoStreamKind) }
    val microphones = composed.map { it.memberId }.distinct()
        .filter { it in withMicrophone }
        .map { MatrixRtcStreamRef(it, MatrixRtcStreamKind.MICROPHONE) }
    return (tiles + microphones).distinct()
}

/** The remote members we should be playing: everyone else publishing a microphone stream, muted or not. */
internal fun microphoneMembers(participants: List<MatrixRtcParticipant>, localMemberId: String): Set<String> =
    participants
        .filter { it.memberId != localMemberId && !it.isLocal && it.streams.any { stream -> stream.kind == MatrixRtcStreamKind.MICROPHONE } }
        .mapTo(mutableSetOf()) { it.memberId }
