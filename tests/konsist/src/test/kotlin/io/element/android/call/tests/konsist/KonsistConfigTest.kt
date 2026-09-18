/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.tests.konsist

import com.google.common.truth.Truth.assertThat
import com.lemonappdev.konsist.api.Konsist
import org.junit.Test

class KonsistConfigTest {
    @Test
    fun `assert that Konsist detect the project classes`() {
        assertThat(
            Konsist
                .scopeFromProject()
                .classes()
        ).isNotEmpty()
    }

    @Test
    fun `assert that Konsist detect the test classes`() {
        assertThat(
            Konsist
                .scopeFromTest()
                .classes()
        ).isNotEmpty()
    }

    @Test
    fun `assert that Konsist sees the library modules`() {
        val modules = Konsist
            .scopeFromProduction()
            .files
            .map { it.moduleName }
            .toSet()
        assertThat(modules).containsAtLeast("call/api", "call/impl", "call/ui")
    }
}
