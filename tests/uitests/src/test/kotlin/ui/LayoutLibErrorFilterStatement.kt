/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package ui

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class LayoutLibErrorFilterStatement : TestRule {
    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            @Throws(Throwable::class)
            override fun evaluate() {
                try {
                    base.evaluate()
                } catch (e: NoSuchMethodError) {
                    if (e.message?.contains("setPosixNicenessInternal") != true) throw e
                }
            }
        }
    }
}
