/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Only a refusal the system gave without its dialog is explained (042 R11). */
class IsRefusedWithoutAskingTest {
    @Test
    fun `only a refusal with no rationale before or after is one the system gave without asking`() {
        assertThat(isRefusedWithoutAsking(granted = false, rationaleBefore = false, rationaleAfter = false)).isTrue()
        // Never asked, then refused in the dialog.
        assertThat(isRefusedWithoutAsking(granted = false, rationaleBefore = false, rationaleAfter = true)).isFalse()
        // Asked again after one refusal, refused in the dialog.
        assertThat(isRefusedWithoutAsking(granted = false, rationaleBefore = true, rationaleAfter = false)).isFalse()
        assertThat(isRefusedWithoutAsking(granted = false, rationaleBefore = true, rationaleAfter = true)).isFalse()
        assertThat(isRefusedWithoutAsking(granted = true, rationaleBefore = false, rationaleAfter = false)).isFalse()
    }
}
