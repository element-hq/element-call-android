/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcStreamState
import io.element.android.call.test.aCameraParticipant
import org.junit.Test

class MicrophoneMembersTest {
    @Test
    fun `everyone else with a microphone stream is played, muted or not`() {
        val participants = listOf(
            aCameraParticipant("me", isLocal = true, isCameraMuted = false),
            aCameraParticipant("talking", isLocal = false, isCameraMuted = false),
            aCameraParticipant("muted", isLocal = false, isCameraMuted = false).copy(
                streams = listOf(MatrixRtcStreamState(MatrixRtcStreamKind.MICROPHONE, isMuted = true)),
            ),
            aCameraParticipant("silent", isLocal = false, isCameraMuted = false).copy(
                streams = listOf(MatrixRtcStreamState(MatrixRtcStreamKind.CAMERA, isMuted = false)),
            ),
        )

        assertThat(microphoneMembers(participants, localMemberId = "me")).containsExactly("talking", "muted")
    }

    /** Our own row can arrive before the core marks it local; the id is what keeps us from hearing ourselves. */
    @Test
    fun `we are never played back, even before the roster marks us local`() {
        val participants = listOf(aCameraParticipant("me", isLocal = false, isCameraMuted = false))

        assertThat(microphoneMembers(participants, localMemberId = "me")).isEmpty()
    }
}
