/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

// element-call-api: the contract. RTC types and ids, the controller, the host ports (including the
// Matrix transport port), state types and options. No Compose, no SDK, no Rust bindings.
plugins {
    id("io.element.call.android-library")
    id("io.element.call.no-compose")
}

android {
    namespace = "io.element.android.call.api"
}

dependencies {
    api(libs.coroutines.core)
    api(libs.kotlinx.collections.immutable)
    implementation(libs.androidx.annotationjvm)
}
