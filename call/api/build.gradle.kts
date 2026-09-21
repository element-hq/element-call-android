import extension.testCommonDependencies

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
    id("io.element.call.publish")
}

android {
    namespace = "io.element.android.call.api"

    // The only generated code in the library: the version the artifacts are published as and the core
    // release they were built against, for ElementCallVersion. Both come from the root gradle.properties,
    // so a local publish with -PVERSION_NAME=... stamps what it publishes.
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        buildConfigField("String", "VERSION_NAME", "\"${project.property("VERSION_NAME")}\"")
        buildConfigField("String", "MATRIX_RTC_VERSION", "\"${project.property("MATRIX_RTC_VERSION")}\"")
    }
}

dependencies {
    api(libs.coroutines.core)
    api(libs.kotlinx.collections.immutable)
    implementation(libs.androidx.annotationjvm)

    testCommonDependencies(libs)
}
