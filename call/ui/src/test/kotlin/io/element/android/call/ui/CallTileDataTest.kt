/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.rtc.MatrixRtcParticipant
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcStreamState
import io.element.android.call.api.rtc.MatrixRtcTileKind
import io.element.android.call.api.rtc.personTile
import io.element.android.call.test.aTile
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test

class CallTileDataTest {
    @Test
    fun `a member publishing an unmuted microphone is neither muted nor missing one`() {
        val participant = aRemoteParticipant().toTile()

        assertThat(participant.isMuted).isFalse()
    }

    @Test
    fun `a member who muted themselves still has a microphone`() {
        val participant = aRemoteParticipant()
            .copy(streams = persistentListOf(MatrixRtcStreamState(MatrixRtcStreamKind.MICROPHONE, isMuted = true)))
            .toTile()

        assertThat(participant.isMuted).isTrue()
    }

    /**
     * The case that sent us looking: a member the SFU relays to everyone else, whose microphone stream
     * never reaches our roster. The badge says muted, which is the right thing to draw, but the two
     * have to stay distinguishable behind it or the fault has nowhere to show up.
     */
    @Test
    fun `a member with no microphone stream reads as muted`() {
        val participant = aRemoteParticipant()
            .copy(streams = persistentListOf(MatrixRtcStreamState(MatrixRtcStreamKind.CAMERA, isMuted = false)))
            .toTile()

        assertThat(participant.isMuted).isTrue()
    }

    @Test
    fun `a member publishing nothing at all reads the same way`() {
        val participant = aRemoteParticipant().copy(streams = persistentListOf()).toTile()

        assertThat(participant.isMuted).isTrue()
    }

    /**
     * A screen is not expected to carry a microphone, so it must not report the absence of one - that
     * would put a fault notice on the one tile where having no audio is the normal state. Nor is it
     * anybody's voice, so the owner's mute and speaking stay on their camera tile.
     */
    @Test
    fun `a screen share tile never reports a mute or a speaker`() {
        val share = aTile(A_REMOTE_MEMBER_ID, MatrixRtcTileKind.SCREEN_SHARE, isMicrophoneMuted = true, isSpeaking = true)
            .toCallTileData(roomMembers = emptyMap(), isLocal = false, isFrontCamera = false)

        assertThat(share.isScreenShare).isTrue()
        assertThat(share.isMuted).isFalse()
        assertThat(share.isActiveSpeaker).isFalse()
        assertThat(share.hasVideo).isTrue()
    }

    @Test
    fun `a sharer's two tiles have different ids and the camera keeps its own`() {
        val camera = aTile(A_REMOTE_MEMBER_ID).toCallTileData(emptyMap(), isLocal = false, isFrontCamera = false)
        val share = aTile(A_REMOTE_MEMBER_ID, MatrixRtcTileKind.SCREEN_SHARE)
            .toCallTileData(emptyMap(), isLocal = false, isFrontCamera = false)

        assertThat(camera.tileId).isEqualTo(A_REMOTE_MEMBER_ID)
        assertThat(share.tileId).isNotEqualTo(camera.tileId)
        assertThat(share.memberId).isEqualTo(camera.memberId)
    }

    @Test
    fun `speaking comes from the core's tile`() {
        val speaking = aTile(A_REMOTE_MEMBER_ID, isSpeaking = true).toCallTileData(emptyMap(), isLocal = false, isFrontCamera = false)

        assertThat(speaking.isActiveSpeaker).isTrue()
    }

    private fun MatrixRtcParticipant.toTile() =
        personTile().toCallTileData(
            roomMembers = emptyMap(),
            isLocal = isLocal,
            isFrontCamera = false,
        )
}
