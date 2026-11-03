/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.rustsdk

import android.content.Context

/**
 * The SDK's Android hook: its native library exports `Java_org_matrix_rustsdk_Android_init` and expects the app to
 * declare this class and call it, with the library loaded through the JVM, before `initPlatform`. It hands the SDK
 * the JVM and the app's context for its TLS verifier. Element X declares the same.
 */
object Android {
    @JvmStatic
    external fun init(context: Context)
}
