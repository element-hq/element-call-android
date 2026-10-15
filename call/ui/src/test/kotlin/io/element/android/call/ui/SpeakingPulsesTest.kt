/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpeakingPulsesTest {
    @Test
    fun `a pulse fades in, then fades as it grows, gone at its largest`() {
        assertThat(pulseAlpha(0f)).isEqualTo(0f)
        assertThat(pulseAlpha(0.05f)).isLessThan(pulseAlpha(0.1f))
        assertThat(pulseAlpha(0.5f)).isLessThan(pulseAlpha(0.1f))
        assertThat(pulseAlpha(0.9f)).isLessThan(pulseAlpha(0.5f))
        assertThat(pulseAlpha(1f)).isEqualTo(0f)
    }
}
