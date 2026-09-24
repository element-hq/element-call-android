/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.rtc.MatrixRtcTileKind
import io.element.android.call.test.aRoster
import io.element.android.call.test.aTile
import org.junit.Test

class PlaybackCandidatesTest {
    /** A sharer is two tiles and one voice: their person tile is the candidate, their screen is not. */
    @Test
    fun `every remote person tile is a candidate, once`() {
        val order = aRoster(aTile("A", MatrixRtcTileKind.SCREEN_SHARE), aTile("A"), aTile("B")).order

        assertThat(playbackCandidates(order, localMemberId = "me")).containsExactly("A", "B")
    }

    /** Our own tile is never in the order, but the id guard holds even if it were. */
    @Test
    fun `we are never a candidate`() {
        val order = aRoster(aTile("me")).order

        assertThat(playbackCandidates(order, localMemberId = "me")).isEmpty()
    }
}
