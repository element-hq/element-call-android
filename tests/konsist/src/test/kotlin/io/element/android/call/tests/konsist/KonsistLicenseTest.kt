/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.tests.konsist

import com.google.common.truth.Truth.assertThat
import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.Test

/**
 * The dual licence (spec rule R10) is enforced on every source file here.
 */
class KonsistLicenseTest {
    private val publicLicense = """
        /\*
        (?:.*\n)* \* Copyright \(c\) 20\d\d((, |-)20\d\d)? Element Creations Ltd\.
        (?:.*\n)* \*
         \* SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial\.
         \* Please see LICENSE files in the repository root for full details\.
         \*/
        """.trimIndent().toRegex()

    @Test
    fun `assert that files have the correct license header`() {
        Konsist
            .scopeFromProject()
            .files
            .also {
                assertThat(it).isNotEmpty()
            }
            .assertTrue {
                publicLicense.containsMatchIn(it.text)
            }
    }

    @Test
    fun `assert that files do not have double license header`() {
        Konsist
            .scopeFromProject()
            .files
            .filter {
                it.nameWithExtension != "KonsistLicenseTest.kt"
            }
            .assertTrue {
                it.text.count("Element Creations Ltd.") == 1
            }
    }
}

private fun String.count(subString: String): Int {
    var count = 0
    var index = 0
    while (true) {
        index = indexOf(subString, index)
        if (index == -1) return count
        count++
        index += subString.length
    }
}
