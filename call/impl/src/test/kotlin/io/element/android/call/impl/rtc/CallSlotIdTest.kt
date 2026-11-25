/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CallSlotIdTest {
    @Test
    fun `the room-wide call is m-call-room, lower case`() {
        assertThat(callSlotId(applicationSlotId = null)).isEqualTo("m.call#room")
    }

    @Test
    fun `an application slot follows m-call`() {
        assertThat(callSlotId(applicationSlotId = "breakout")).isEqualTo("m.call#breakout")
    }
}
