/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcStreamRef
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.MatrixRtcTileKind
import org.junit.Test

class StreamsToPollTest {
    @Test
    fun `a sharer's two tiles ask for the camera, the screen and one microphone`() {
        val composed = listOf(
            MatrixRtcTileId("A", MatrixRtcTileKind.SCREEN_SHARE),
            MatrixRtcTileId("A", MatrixRtcTileKind.PERSON),
        )

        val streams = streamsToPoll(composed, withMicrophone = setOf("A"))

        assertThat(streams).containsExactly(
            MatrixRtcStreamRef("A", MatrixRtcStreamKind.SCREEN_SHARE),
            MatrixRtcStreamRef("A", MatrixRtcStreamKind.CAMERA),
            MatrixRtcStreamRef("A", MatrixRtcStreamKind.MICROPHONE),
        ).inOrder()
    }

    /** No microphone stream means nothing to poll for it: the warning about that is logged elsewhere. */
    @Test
    fun `a member without a microphone is asked about their tile only`() {
        val streams = streamsToPoll(listOf(MatrixRtcTileId("A", MatrixRtcTileKind.PERSON)), withMicrophone = emptySet())

        assertThat(streams).containsExactly(MatrixRtcStreamRef("A", MatrixRtcStreamKind.CAMERA))
    }

    @Test
    fun `nothing composed asks for nothing`() {
        assertThat(streamsToPoll(emptyList(), withMicrophone = setOf("A"))).isEmpty()
    }
}
