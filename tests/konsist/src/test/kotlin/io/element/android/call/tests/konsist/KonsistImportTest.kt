/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.tests.konsist

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.Test

class KonsistImportTest {
    @Test
    fun `Functions with '@VisibleForTesting' annotation should use 'androidx' version`() {
        Konsist
            .scopeFromProject()
            .imports
            .assertFalse(
                additionalMessage = "Please use 'androidx.annotation.VisibleForTesting' instead of " +
                    "'org.jetbrains.annotations.VisibleForTesting' (project convention).",
            ) {
                it.name == "org.jetbrains.annotations.VisibleForTesting"
            }
    }

    @Test
    fun `android util Log should not be used`() {
        Konsist
            .scopeFromProject()
            .imports
            .assertFalse(
                additionalMessage = "Please use Timber instead of android.util.Log (the host owns the trees).",
            ) {
                it.name == "android.util.Log"
            }
    }
}
