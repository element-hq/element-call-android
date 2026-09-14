/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.matrix

/**
 * Marks a declaration that exists only because the Rust SDK does not yet expose what the call needs.
 *
 * Everything so marked lives in a `temporary/` folder, has its removal recipe in `docs/FEEDBACK.md`, and
 * is imported by nothing outside that folder; `KonsistBoundaryTest` enforces all three.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
internal annotation class ElementCallTemporaryApi
