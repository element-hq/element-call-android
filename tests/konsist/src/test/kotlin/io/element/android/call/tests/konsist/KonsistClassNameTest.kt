/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.tests.konsist

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.google.common.truth.Truth.assertThat
import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withAllParentsOf
import com.lemonappdev.konsist.api.ext.list.withNameContaining
import com.lemonappdev.konsist.api.ext.list.withNameEndingWith
import com.lemonappdev.konsist.api.verify.assertEmpty
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.Test

class KonsistClassNameTest {
    @Test
    fun `Classes extending 'PreviewParameterProvider' name MUST end with 'PreviewParam' and MUST contain provided class name`() {
        Konsist.scopeFromProduction()
            .classes()
            .withAllParentsOf(PreviewParameterProvider::class)
            .assertTrue { klass ->
                // Cannot find a better way to get the type of the generic
                val providedType = klass.text
                    .substringAfter("PreviewParameterProvider<")
                    .substringBefore(">")
                    // Get the substring before the first '<' to remove the generic type
                    .substringBefore("<")
                    .removeSuffix("?")
                    .replace(".", "")
                val name = klass.name
                name.endsWith("PreviewParam") &&
                    name.contains(providedType)
            }
    }

    @Test
    fun `Fake classes must be named using Fake and the interface it fakes`() {
        var failingCases = 0
        val failingCasesList = listOf(
            "FakeWrongClassName",
            "FakeWrongClassSubInterfaceName",
        )
        Konsist.scopeFromProject()
            .classes()
            .withNameContaining("Fake")
            .assertTrue {
                val interfaceName = it.name
                    .replace("FakeFfi", "")
                    .replace("Fake", "")
                val result = it.name.startsWith("Fake") &&
                    it.parents().any { parent ->
                        val parentName = parent.name.replace(".", "")
                        parentName == interfaceName
                    }
                if (!result && it.name in failingCasesList) {
                    failingCases++
                    true
                } else {
                    result
                }
            }
        assertThat(failingCases).isEqualTo(failingCasesList.size)
    }

    @Test
    fun `Class implementing interface should have name not end with 'Impl' but start with 'Default'`() {
        Konsist.scopeFromProject()
            .classes()
            .withNameEndingWith("Impl")
            .assertEmpty(additionalMessage = "Class implementing interface should have name not end with 'Impl' but start with 'Default'")
    }
}
