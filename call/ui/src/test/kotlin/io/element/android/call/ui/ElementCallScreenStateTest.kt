/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallData
import io.element.android.call.api.ElementCallRoomMember
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.ElementCallVersion
import io.element.android.call.api.rtc.MatrixRtcAudioLevel
import io.element.android.call.api.rtc.MatrixRtcParticipant
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcStreamRef
import io.element.android.call.api.rtc.MatrixRtcTile
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.MatrixRtcTileKind
import io.element.android.call.api.rtc.MatrixRtcVideoConstraints
import io.element.android.call.api.rtc.id.UserId
import io.element.android.call.test.A_ROOM_ID
import io.element.android.call.test.FakeElementCallController
import io.element.android.call.test.aCameraParticipant
import io.element.android.call.test.aSharingParticipant
import io.element.android.call.test.aTile
import io.element.android.call.test.audio.aSpeaker
import io.element.android.call.tests.testutils.WarmUpRule
import io.element.android.call.tests.testutils.consumeItemsUntilPredicate
import io.element.android.call.tests.testutils.consumeItemsUntilTimeout
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

private const val ANOTHER_REMOTE_MEMBER_ID = "3d7f0c7c6f2a4d0b9e8a1c5b7d6e4f21"
private val A_ROOM_MEMBER = ElementCallRoomMember(userId = UserId("@bob:example.org"), displayName = "Bob", avatarUrl = null)

/**
 * What the screen makes of a snapshot, and what its events ask the controller for.
 *
 * Over [FakeElementCallController], so nothing here joins a call: the test writes the snapshot and
 * reads the state. How the snapshot comes to say what it says is `DefaultElementCallControllerTest`'s
 * business, in the impl module.
 */
class ElementCallScreenStateTest {
    @get:Rule val warmUpRule = WarmUpRule()

    @Test
    fun `no call yet reads as waiting for the microphone permission`() = runTest {
        val controller = FakeElementCallController()
        var closeCount = 0

        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNavigator(onClose = { closeCount++ })) }.test {
            val initialState = awaitItem()
            assertThat(initialState.connection).isEqualTo(ElementCallConnection.RequestingPermission)
            assertThat(initialState.isMicrophonePermissionGranted).isFalse()
            assertThat(initialState.memberCount).isEqualTo(0)
            assertThat(initialState.tiles).isEmpty()
            assertThat(initialState.libraryVersion).isEqualTo(ElementCallVersion.library)
            assertThat(initialState.coreVersion).isEqualTo(ElementCallVersion.core)
            // "Not yet" is not "over": the screen must not dismiss a call that has not started.
            assertThat(closeCount).isEqualTo(0)
        }
    }

    @Test
    fun `the call going away closes the screen, once it has existed`() = runTest {
        val controller = FakeElementCallController(initialState = aConnectedSnapshot())
        var closeCount = 0

        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNavigator(onClose = { closeCount++ })) }.test {
            assertThat(awaitItem().connection).isEqualTo(ElementCallConnection.Connected)

            controller.state.value = null

            consumeItemsUntilPredicate { it.connection == ElementCallConnection.RequestingPermission }
            assertThat(closeCount).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the snapshot is shaped into screen state`() = runTest {
        val controller = FakeElementCallController(
            initialState = aConnectedSnapshot().copy(
                memberCount = 3,
                isMicrophoneMuted = true,
                isCameraEnabled = true,
                isFrontCamera = false,
                isCameraPermissionGranted = true,
                isScreenShareAvailable = true,
                isScreenSharing = true,
                isTileStatsVisible = true,
                audioLevels = persistentMapOf(A_REMOTE_MEMBER_ID to MatrixRtcAudioLevel(level = 0.5f, frameCount = 42)),
                roomName = "Paulina",
                isDm = true,
                connectedAtElapsedMs = 1_000L,
            ),
        )

        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNavigator()) }.test {
            val state = awaitItem()
            assertThat(state.connection).isEqualTo(ElementCallConnection.Connected)
            assertThat(state.memberCount).isEqualTo(3)
            assertThat(state.isMicrophoneMuted).isTrue()
            assertThat(state.isCameraEnabled).isTrue()
            assertThat(state.isFrontCamera).isFalse()
            assertThat(state.isCameraPermissionGranted).isTrue()
            assertThat(state.isScreenShareAvailable).isTrue()
            assertThat(state.isScreenSharing).isTrue()
            assertThat(state.isTileStatsVisible).isTrue()
            assertThat(state.audioLevels[A_REMOTE_MEMBER_ID]).isEqualTo(MatrixRtcAudioLevel(level = 0.5f, frameCount = 42))
            assertThat(state.roomName).isEqualTo("Paulina")
            assertThat(state.isDm).isTrue()
            assertThat(state.connectedAtElapsedMs).isEqualTo(1_000L)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `answers to the permission prompts reach the controller`() = runTest {
        val controller = FakeElementCallController()

        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNavigator()) }.test {
            val state = awaitItem()
            state.eventSink(ElementCallScreenEvent.SetMicrophonePermissionGranted(true))
            state.eventSink(ElementCallScreenEvent.SetCameraPermissionGranted(false))

            assertThat(controller.microphonePermissionAnswers).containsExactly(true)
            assertThat(controller.cameraPermissionAnswers).containsExactly(false)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Toggles flip what the snapshot says, so the screen never has to remember a state of its own. */
    @Test
    fun `toggling the microphone and the test tone ask for the opposite of the snapshot`() = runTest {
        val controller = FakeElementCallController(initialState = aConnectedSnapshot().copy(isMicrophoneMuted = true))

        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNavigator()) }.test {
            val state = awaitItem()
            state.eventSink(ElementCallScreenEvent.ToggleMicrophoneMuted)
            state.eventSink(ElementCallScreenEvent.ToggleAudioTestTone)

            assertThat(controller.microphoneMutedCalls).containsExactly(false)
            assertThat(controller.audioTestToneCalls).containsExactly(true)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The camera is asked for on the tap, not up front with the microphone - so tapping without it
     * must ask and nothing more. Enabling anyway would open a camera we have no right to and fail
     * asynchronously somewhere in Camera2, with the UI already showing the camera as on.
     */
    @Test
    fun `tapping the camera without permission asks for it and starts nothing`() = runTest {
        val controller = FakeElementCallController(initialState = aConnectedSnapshot())
        var requestCount = 0

        moleculeFlow(RecompositionMode.Immediate) {
            rememberElementCallScreenState(controller, aNavigator(onRequestCameraPermission = { requestCount++ }))
        }.test {
            val connected = awaitItem()
            assertThat(connected.isCameraPermissionGranted).isFalse()

            connected.eventSink(ElementCallScreenEvent.ToggleCamera)

            assertThat(requestCount).isEqualTo(1)
            assertThat(controller.cameraEnabledCalls).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `tapping the camera with permission toggles it`() = runTest {
        val controller = FakeElementCallController(
            initialState = aConnectedSnapshot().copy(isCameraPermissionGranted = true, isCameraEnabled = true),
        )
        var requestCount = 0

        moleculeFlow(RecompositionMode.Immediate) {
            rememberElementCallScreenState(controller, aNavigator(onRequestCameraPermission = { requestCount++ }))
        }.test {
            awaitItem().eventSink(ElementCallScreenEvent.ToggleCamera)

            assertThat(requestCount).isEqualTo(0)
            assertThat(controller.cameraEnabledCalls).containsExactly(false)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Unlike the camera there is nothing to check before sharing: the grant is spent when it is used,
     * so every share starts at the system dialog. Stopping needs no dialog and goes straight through.
     */
    @Test
    fun `sharing the screen starts at the system dialog and stopping does not`() = runTest {
        val controller = FakeElementCallController(initialState = aConnectedSnapshot().copy(isScreenShareAvailable = true))
        var requestCount = 0

        moleculeFlow(RecompositionMode.Immediate) {
            rememberElementCallScreenState(controller, aNavigator(onRequestScreenCapture = { requestCount++ }))
        }.test {
            awaitItem().eventSink(ElementCallScreenEvent.ToggleScreenShare)
            assertThat(requestCount).isEqualTo(1)
            assertThat(controller.screenShareTokens).isEmpty()

            controller.state.value = aConnectedSnapshot().copy(isScreenShareAvailable = true, isScreenSharing = true)
            consumeItemsUntilPredicate { it.isScreenSharing }.last().eventSink(ElementCallScreenEvent.ToggleScreenShare)

            assertThat(requestCount).isEqualTo(1)
            assertThat(controller.screenShareTokens).containsExactly(null)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the remaining controls are forwarded as they are`() = runTest {
        val controller = FakeElementCallController(initialState = aConnectedSnapshot())
        val constraints = MatrixRtcVideoConstraints(isVisible = true, widthPx = 320, heightPx = 240)

        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNavigator()) }.test {
            val state = awaitItem()
            state.eventSink(ElementCallScreenEvent.SwitchCamera)
            state.eventSink(ElementCallScreenEvent.SelectAudioDevice(aSpeaker()))
            state.eventSink(ElementCallScreenEvent.Minimize)
            state.eventSink(ElementCallScreenEvent.ToggleTileStats)
            state.eventSink(ElementCallScreenEvent.SetVideoConstraints(A_REMOTE_MEMBER_ID, MatrixRtcStreamKind.CAMERA, constraints))
            state.eventSink(ElementCallScreenEvent.SetComposedTiles(setOf(MatrixRtcTileId(A_REMOTE_MEMBER_ID, MatrixRtcTileKind.PERSON))))
            state.eventSink(ElementCallScreenEvent.HangUp)

            assertThat(controller.switchCameraCount).isEqualTo(1)
            assertThat(controller.selectedAudioDevices).containsExactly(aSpeaker())
            assertThat(controller.maximizedCalls).containsExactly(false)
            assertThat(controller.toggleTileStatsCount).isEqualTo(1)
            assertThat(controller.videoConstraints).containsExactly(
                MatrixRtcStreamRef(A_REMOTE_MEMBER_ID, MatrixRtcStreamKind.CAMERA) to constraints,
            )
            assertThat(controller.composedTiles).containsExactly(setOf(MatrixRtcTileId(A_REMOTE_MEMBER_ID, MatrixRtcTileKind.PERSON)))
            assertThat(controller.hangUpCount).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Nobody is drawn twice: the spotlight and the strip never show the same member.
     *
     * Both halves of what this cost are worth keeping pinned. Visually it reads as a glitch - the
     * same face above itself. On the video path it was a crash: two tiles meant two collectors on a
     * cold flow that opens one stream each.
     */
    @Test
    fun `the spotlighted member is not drawn again in the strip`() = runTest {
        val controller = FakeElementCallController(
            initialState = aConnectedSnapshot(
                participants = listOf(
                    aCameraParticipant(A_LOCAL_MEMBER_ID, isLocal = true, isCameraMuted = false),
                    aCameraParticipant(A_REMOTE_MEMBER_ID, isLocal = false, isCameraMuted = false),
                ),
            ),
        )

        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNavigator()) }.test {
            val state = awaitItem()

            assertThat(state.tiles).hasSize(2)
            // The remote is spotlighted, so only we are left in the strip.
            assertThat(state.spotlightTile?.memberId).isEqualTo(A_REMOTE_MEMBER_ID)
            assertThat(state.stripTiles.map { it.memberId }).containsExactly(A_LOCAL_MEMBER_ID)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Our own tile first, then the core's order exactly as given: the core already ranked and damped
     * it, so a re-sort here would fight it. The spotlight is the head of that order, never us.
     */
    @Test
    fun `our own tile comes first and the core's order is kept after it`() = runTest {
        val participants = listOf(
            aCameraParticipant(A_LOCAL_MEMBER_ID, isLocal = true, isCameraMuted = false),
            aCameraParticipant(A_REMOTE_MEMBER_ID, isLocal = false, isCameraMuted = false),
            aCameraParticipant(ANOTHER_REMOTE_MEMBER_ID, isLocal = false, isCameraMuted = false),
        )
        val controller = FakeElementCallController(
            initialState = aConnectedSnapshot(
                participants = participants,
                // Not the participant order: the core put the other remote first.
                tiles = listOf(aTile(ANOTHER_REMOTE_MEMBER_ID), aTile(A_REMOTE_MEMBER_ID)),
            ),
        )

        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNavigator()) }.test {
            val state = awaitItem()

            assertThat(state.tiles.map { it.memberId }).containsExactly(A_LOCAL_MEMBER_ID, ANOTHER_REMOTE_MEMBER_ID, A_REMOTE_MEMBER_ID).inOrder()
            assertThat(state.spotlightTile?.memberId).isEqualTo(ANOTHER_REMOTE_MEMBER_ID)
            assertThat(state.stripTiles.map { it.memberId }).containsExactly(A_LOCAL_MEMBER_ID, A_REMOTE_MEMBER_ID).inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** The local user acts on their own tile, so a tap has to show before the core's tile catches up. */
    @Test
    fun `our own mute and camera come from the call rather than the core's tile`() = runTest {
        val controller = FakeElementCallController(
            initialState = aConnectedSnapshot(
                participants = listOf(aCameraParticipant(A_LOCAL_MEMBER_ID, isLocal = true, isCameraMuted = false)),
            ).copy(isMicrophoneMuted = true, isCameraEnabled = false),
        )

        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNavigator()) }.test {
            val own = awaitItem().tiles.single()

            assertThat(own.isLocal).isTrue()
            assertThat(own.isMuted).isTrue()
            assertThat(own.hasVideo).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * A member's camera tile is the same tile whether or not they are sharing: same key, so the same
     * composable and the same renderer carry on while their screen comes and goes.
     */
    @Test
    fun `a camera tile keeps its identity when a share starts and stops`() = runTest {
        val controller = FakeElementCallController(
            initialState = aConnectedSnapshot(tiles = listOf(aTile(A_REMOTE_MEMBER_ID))),
        )

        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNavigator()) }.test {
            val before = awaitItem().tiles.single().tileId

            controller.state.value = controller.state.value?.copy(
                tiles = listOf(aTile(A_REMOTE_MEMBER_ID, MatrixRtcTileKind.SCREEN_SHARE), aTile(A_REMOTE_MEMBER_ID)).toImmutableList(),
            )
            val sharing = consumeItemsUntilPredicate { it.tiles.size == 2 }.last()
            assertThat(sharing.tiles.single { !it.isScreenShare }.tileId).isEqualTo(before)

            controller.state.value = controller.state.value?.copy(tiles = listOf(aTile(A_REMOTE_MEMBER_ID)).toImmutableList())
            val after = consumeItemsUntilPredicate { it.tiles.size == 1 }.last()
            assertThat(after.tiles.single().tileId).isEqualTo(before)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Alone in a call, there is nobody to spotlight - and we must not spotlight ourselves, which
     * would draw us both above and inside the strip.
     */
    @Test
    fun `a call with nobody else in it has no spotlight`() = runTest {
        val controller = FakeElementCallController(
            initialState = aConnectedSnapshot(
                participants = listOf(aCameraParticipant(A_LOCAL_MEMBER_ID, isLocal = true, isCameraMuted = false)),
            ),
        )

        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNavigator()) }.test {
            val state = awaitItem()

            assertThat(state.spotlightTile).isNull()
            assertThat(state.stripTiles.map { it.memberId }).containsExactly(A_LOCAL_MEMBER_ID)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Which members get a tile, and the rule that decides it. A muted camera track still exists on
     * the roster, so keying off the stream's presence alone would leave a frozen tile after someone
     * turns their camera off - and would open a video stream to decode frames nobody is sending.
     */
    @Test
    fun `only members publishing an unmuted camera get video`() = runTest {
        val controller = FakeElementCallController(
            initialState = aConnectedSnapshot(
                participants = listOf(
                    aCameraParticipant(A_LOCAL_MEMBER_ID, isLocal = true, isCameraMuted = false),
                    aCameraParticipant(A_REMOTE_MEMBER_ID, isLocal = false, isCameraMuted = true),
                    aRemoteParticipant().copy(memberId = A_STALE_MEMBER_ID),
                ),
            ),
        )

        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNavigator()) }.test {
            val state = awaitItem()

            // The unmuted camera only: not the muted one, and not the audio-only member.
            assertThat(state.videoFrames.keys).containsExactly(A_LOCAL_MEMBER_ID)
            assertThat(controller.videoFramesRequests.map { it.memberId }.distinct()).containsExactly(A_LOCAL_MEMBER_ID)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * A member sharing their screen is two tiles with two independent video streams, and the two must
     * not collide.
     *
     * Keyed by member id alone - which is how this worked before screen share existed - the person and
     * their screen share one key, so Compose reuses one composable for both and the second stream to
     * be asked for silently gets the first one's frames. The same collision on the RTC side is what
     * crashed the core inside `VideoSinkWrapper::on_frame`.
     */
    @Test
    fun `a member sharing their screen gets a tile and a stream for each`() = runTest {
        val controller = FakeElementCallController(
            initialState = aConnectedSnapshot(participants = listOf(aSharingParticipant(A_REMOTE_MEMBER_ID))),
        )

        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNavigator()) }.test {
            val state = awaitItem()

            // Two tiles, two distinct keys, two frame streams.
            assertThat(state.tiles.map { it.tileId })
                .containsExactly(A_REMOTE_MEMBER_ID, "$A_REMOTE_MEMBER_ID#SCREEN_SHARE")
            assertThat(state.videoFrames.keys)
                .containsExactly(A_REMOTE_MEMBER_ID, "$A_REMOTE_MEMBER_ID#SCREEN_SHARE")
            assertThat(controller.videoFramesRequests.map { it.kind }.distinct())
                .containsExactly(MatrixRtcStreamKind.CAMERA, MatrixRtcStreamKind.SCREEN_SHARE)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * A shared screen takes the spotlight, and the person sharing it stays in the strip.
     *
     * The screen beats the active speaker outright rather than competing with them: somebody shares a
     * screen in order for it to be looked at, and a spotlight that flicked away every time they spoke
     * would be worse than one that never moved.
     */
    @Test
    fun `a shared screen takes the spotlight and its owner stays in the strip`() = runTest {
        val controller = FakeElementCallController(
            initialState = aConnectedSnapshot(
                participants = listOf(
                    aSharingParticipant(A_REMOTE_MEMBER_ID),
                    aCameraParticipant(ANOTHER_REMOTE_MEMBER_ID, isLocal = false, isCameraMuted = false),
                ),
            ),
        )

        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNavigator()) }.test {
            val state = awaitItem()

            assertThat(state.spotlightTile?.isScreenShare).isTrue()
            assertThat(state.spotlightTile?.tileId).isEqualTo("$A_REMOTE_MEMBER_ID#SCREEN_SHARE")
            // The sharer is still shown - only their screen was promoted, not them.
            assertThat(state.stripTiles.map { it.tileId }).containsExactly(A_REMOTE_MEMBER_ID, ANOTHER_REMOTE_MEMBER_ID)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Our own screen is published but never drawn: nobody needs to be shown their own screen. The core
     * makes no tile for it; this pins that the own tile we build is only ever our camera.
     */
    @Test
    fun `our own screen share does not become a tile`() = runTest {
        val controller = FakeElementCallController(
            initialState = aConnectedSnapshot(participants = listOf(aSharingParticipant(A_LOCAL_MEMBER_ID, isLocal = true))),
        )

        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNavigator()) }.test {
            val state = awaitItem()

            assertThat(state.tiles.map { it.tileId }).containsExactly(A_LOCAL_MEMBER_ID)
            assertThat(state.videoFrames.keys).doesNotContain("$A_LOCAL_MEMBER_ID#SCREEN_SHARE")
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Names and faces come from the room, and a member the room has not described yet still gets a tile. */
    @Test
    fun `tiles are joined onto the room members when the room knows them`() = runTest {
        val remote = aCameraParticipant(A_REMOTE_MEMBER_ID, isLocal = false, isCameraMuted = false, userId = A_ROOM_MEMBER.userId)
        val controller = FakeElementCallController(
            initialState = aConnectedSnapshot(
                participants = listOf(
                    aCameraParticipant(A_LOCAL_MEMBER_ID, isLocal = true, isCameraMuted = false),
                    remote,
                ),
            ).copy(roomMembers = persistentMapOf(A_ROOM_MEMBER.userId to A_ROOM_MEMBER)),
        )

        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNavigator()) }.test {
            val state = awaitItem()

            assertThat(state.tiles.single { it.memberId == A_REMOTE_MEMBER_ID }.roomMember).isEqualTo(A_ROOM_MEMBER)
            assertThat(state.tiles.single { it.memberId == A_LOCAL_MEMBER_ID }.roomMember).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Compose keys the tile's collection on the flow, so a fresh instance per composition would
     * reopen the video stream - and the core would restart decoding - on every recomposition.
     */
    @Test
    fun `a member keeps the same video flow across recompositions`() = runTest {
        val controller = FakeElementCallController(
            initialState = aConnectedSnapshot(
                participants = listOf(aCameraParticipant(A_LOCAL_MEMBER_ID, isLocal = true, isCameraMuted = false)),
            ),
        )

        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNavigator()) }.test {
            val first = awaitItem()
            // Force more recompositions without changing who has video.
            controller.state.value = controller.state.value?.copy(
                audioLevels = persistentMapOf(A_LOCAL_MEMBER_ID to MatrixRtcAudioLevel(level = 0.4f, frameCount = 10)),
            )
            val later = consumeItemsUntilTimeout().last()

            assertThat(later.videoFrames[A_LOCAL_MEMBER_ID]).isSameInstanceAs(first.videoFrames[A_LOCAL_MEMBER_ID])
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * A DM with the two of us in it is drawn one-to-one: the room says it is a DM, and there are
     * exactly two camera tiles. Both halves come from different places - the room and the media
     * roster - and this is where they meet.
     */
    @Test
    fun `a DM call with one other person lays out one to one`() = runTest {
        val controller = FakeElementCallController(
            initialState = aConnectedSnapshot(
                participants = listOf(
                    aCameraParticipant(A_LOCAL_MEMBER_ID, isLocal = true, isCameraMuted = false),
                    aCameraParticipant(A_REMOTE_MEMBER_ID, isLocal = false, isCameraMuted = false),
                ),
                isDm = true,
            ),
        )

        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNavigator()) }.test {
            val state = awaitItem()

            assertThat(state.layout).isEqualTo(CallLayout.OneToOne)
            // The other person is the one who fills the screen, never us.
            assertThat(state.spotlightTile?.memberId).isEqualTo(A_REMOTE_MEMBER_ID)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** A third person has nowhere to go in a two-tile layout, so a DM they have joined is drawn as a group. */
    @Test
    fun `a DM call with a third participant lays out as a group`() = runTest {
        val controller = FakeElementCallController(
            initialState = aConnectedSnapshot(
                participants = listOf(
                    aCameraParticipant(A_LOCAL_MEMBER_ID, isLocal = true, isCameraMuted = false),
                    aCameraParticipant(A_REMOTE_MEMBER_ID, isLocal = false, isCameraMuted = false),
                    aCameraParticipant(ANOTHER_REMOTE_MEMBER_ID, isLocal = false, isCameraMuted = false),
                ),
                isDm = true,
            ),
        )

        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNavigator()) }.test {
            assertThat(awaitItem().layout).isEqualTo(CallLayout.Group)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** A shared screen is a third tile, and the group layout is the one that knows to spotlight it. */
    @Test
    fun `a DM call where the other person shares their screen lays out as a group`() = runTest {
        val controller = FakeElementCallController(
            initialState = aConnectedSnapshot(
                participants = listOf(
                    aCameraParticipant(A_LOCAL_MEMBER_ID, isLocal = true, isCameraMuted = false),
                    aSharingParticipant(A_REMOTE_MEMBER_ID),
                ),
                isDm = true,
            ),
        )

        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNavigator()) }.test {
            val state = awaitItem()

            assertThat(state.tiles).hasSize(3)
            assertThat(state.layout).isEqualTo(CallLayout.Group)
            assertThat(state.spotlightTile?.isScreenShare).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Two people in a room that is not a DM are still a group: the layout follows the room, not the head count. */
    @Test
    fun `a call in a room with one other person lays out as a group`() = runTest {
        val controller = FakeElementCallController(
            initialState = aConnectedSnapshot(
                participants = listOf(
                    aCameraParticipant(A_LOCAL_MEMBER_ID, isLocal = true, isCameraMuted = false),
                    aCameraParticipant(A_REMOTE_MEMBER_ID, isLocal = false, isCameraMuted = false),
                ),
                isDm = false,
            ),
        )

        moleculeFlow(RecompositionMode.Immediate) { rememberElementCallScreenState(controller, aNavigator()) }.test {
            val state = awaitItem()

            assertThat(state.isDm).isFalse()
            assertThat(state.layout).isEqualTo(CallLayout.Group)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** The core's tiles default to its shape for [participants]; pass [tiles] to rank them yourself. */
    private fun aConnectedSnapshot(
        participants: List<MatrixRtcParticipant> = emptyList(),
        tiles: List<MatrixRtcTile> = participants.previewTiles(),
        isDm: Boolean = false,
    ) = ElementCallSnapshot(
        callData = ElementCallData(roomId = A_ROOM_ID, isAudioCall = true),
        connection = ElementCallConnection.Connected,
        isMicrophonePermissionGranted = true,
        participants = participants.toImmutableList(),
        tiles = tiles.toImmutableList(),
        ownTile = participants.previewOwnTile(),
        isCameraEnabled = participants.previewOwnTile()?.hasVideo == true,
        isDm = isDm,
    )

    private fun aNavigator(
        onClose: () -> Unit = {},
        onRequestCameraPermission: () -> Unit = {},
        onRequestScreenCapture: () -> Unit = {},
    ) = object : ElementCallNavigator {
        override fun close() = onClose()
        override fun requestCameraPermission() = onRequestCameraPermission()
        override fun requestScreenCapture() = onRequestScreenCapture()
    }
}
