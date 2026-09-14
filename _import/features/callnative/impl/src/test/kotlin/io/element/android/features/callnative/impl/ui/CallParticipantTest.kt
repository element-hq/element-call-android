/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.callnative.impl.ui

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrixrtc.api.MatrixRtcStreamKind
import io.element.android.libraries.matrixrtc.api.MatrixRtcStreamState
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test

class CallParticipantTest {
    @Test
    fun `a member publishing an unmuted microphone is neither muted nor missing one`() {
        val participant = aRemoteParticipant().toParticipant()

        assertThat(participant.isMuted).isFalse()
        assertThat(participant.hasMicrophone).isTrue()
    }

    @Test
    fun `a member who muted themselves still has a microphone`() {
        val participant = aRemoteParticipant()
            .copy(streams = persistentListOf(MatrixRtcStreamState(MatrixRtcStreamKind.MICROPHONE, isMuted = true)))
            .toParticipant()

        assertThat(participant.isMuted).isTrue()
        assertThat(participant.hasMicrophone).isTrue()
    }

    /**
     * The case that sent us looking: a member the SFU relays to everyone else, whose microphone stream
     * never reaches our roster. The badge says muted, which is the right thing to draw, but the two
     * have to stay distinguishable behind it or the fault has nowhere to show up.
     */
    @Test
    fun `a member with no microphone stream reads as muted but is marked as having none`() {
        val participant = aRemoteParticipant()
            .copy(streams = persistentListOf(MatrixRtcStreamState(MatrixRtcStreamKind.CAMERA, isMuted = false)))
            .toParticipant()

        assertThat(participant.isMuted).isTrue()
        assertThat(participant.hasMicrophone).isFalse()
    }

    @Test
    fun `a member publishing nothing at all reads the same way`() {
        val participant = aRemoteParticipant().copy(streams = persistentListOf()).toParticipant()

        assertThat(participant.isMuted).isTrue()
        assertThat(participant.hasMicrophone).isFalse()
    }

    /**
     * A screen is not expected to carry a microphone, so it must not report the absence of one - that
     * would put a fault notice on the one tile where having no audio is the normal state.
     */
    @Test
    fun `a screen share tile never reports a missing microphone`() {
        val tiles = aRemoteParticipant()
            .copy(
                streams = persistentListOf(
                    MatrixRtcStreamState(MatrixRtcStreamKind.SCREEN_SHARE, isMuted = false),
                )
            )
            .toCallTiles(roomMembers = emptyMap(), activeSpeakerIds = emptySet(), isFrontCamera = false)

        val screen = tiles.single { it.isScreenShare }
        assertThat(screen.hasMicrophone).isTrue()
        assertThat(screen.isMuted).isFalse()
        // The person behind it still reports the truth: they publish a screen and no microphone.
        assertThat(tiles.single { !it.isScreenShare }.hasMicrophone).isFalse()
    }

    private fun io.element.android.libraries.matrixrtc.api.MatrixRtcParticipant.toParticipant() =
        toCallParticipant(roomMembers = emptyMap(), activeSpeakerIds = emptySet(), isFrontCamera = false)
}
