/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api

/**
 * Which Element Call this is, stamped at build time.
 *
 * Both are safe to log and are what the call screen's overflow menu shows. Between releases [library]
 * reads as the last release, and `0.0.0` before there is one, because the release process is what stamps
 * `VERSION_NAME` (RELEASING.md).
 */
object ElementCallVersion {
    /** The version the artifacts were published as: `VERSION_NAME` in the root `gradle.properties`. */
    val library: String = BuildConfig.VERSION_NAME

    /** The matrix-rust-rtc release the artifacts were built against: `MATRIX_RTC_VERSION`. */
    val core: String = BuildConfig.MATRIX_RTC_VERSION
}
