/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.rtc.MatrixRtcCallEvent
import io.element.android.call.api.rtc.id.UserId
import org.junit.Test
import org.matrix.rtc.FfiCallEvent

class CallEventMapperTest {
    @Test
    fun `a join carries the count after it`() {
        val mapped = FfiCallEvent.ParticipantJoined(memberId = "bob", userId = "@bob:example.org", participantCount = 3u).map()

        assertThat(mapped).isEqualTo(MatrixRtcCallEvent.ParticipantJoined("bob", UserId("@bob:example.org"), participantCount = 3))
    }

    @Test
    fun `a leave carries the count after it`() {
        val mapped = FfiCallEvent.ParticipantLeft(memberId = "bob", participantCount = 2u).map()

        assertThat(mapped).isEqualTo(MatrixRtcCallEvent.ParticipantLeft("bob", participantCount = 2))
    }
}
