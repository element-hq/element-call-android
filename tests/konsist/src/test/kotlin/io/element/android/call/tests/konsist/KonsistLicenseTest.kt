/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.tests.konsist

import com.google.common.truth.Truth.assertThat
import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.Test

/**
 * The dual licence (spec rule R10) is enforced on every source file here.
 */
class KonsistLicenseTest {
    /**
     * The one header, exactly, at the very top of the file: one copyright line, one holder, one year.
     * Nothing optional between the opening and the copyright line, which is what let a second holder's
     * line through before. `tools/quality/fix_headers.py` writes this shape; `tools/quality/check.sh`
     * checks the files Konsist does not see (XML, scripts).
     */
    private val publicLicense = """
        ^/\*
         \* Copyright \(c\) 20\d\d Element Creations Ltd\.
         \*
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

    @Test
    fun `assert that files name no other copyright holder`() {
        Konsist
            .scopeFromProject()
            .files
            .filter {
                it.nameWithExtension != "KonsistLicenseTest.kt"
            }
            .assertFalse(additionalMessage = "This repository's files are Element Creations Ltd.'s alone; run tools/quality/fix_headers.py") {
                it.text.contains("New Vector")
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
