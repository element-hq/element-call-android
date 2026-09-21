/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ElementCallVersionTest {
    @Test
    fun `both versions are stamped`() {
        assertThat(ElementCallVersion.library).isNotEmpty()
        assertThat(ElementCallVersion.core).isNotEmpty()
    }
}
