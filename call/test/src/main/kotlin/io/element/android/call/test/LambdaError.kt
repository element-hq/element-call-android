/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.test

/**
 * The default body of a fake's lambda: a test that reaches it forgot to provide one. Kept here rather
 * than taken from `tests/testutils`, which is not published, so that the fakes carry no dependency a
 * host cannot resolve.
 */
fun lambdaError(
    message: String = "This lambda should never be called."
): Nothing {
    throw AssertionError(message)
}
