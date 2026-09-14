import extension.testCommonDependencies

/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

// element-call-matrix: the turnkey Matrix transport over the Rust SDK, and the temporary
// widget-driver stopgap. The ONLY module importing org.matrix.rustcomponents.sdk. No Compose.
plugins {
    id("io.element.call.android-library")
    id("io.element.call.no-compose")
}

android {
    namespace = "io.element.android.call.matrix"
}

dependencies {
    api(projects.call.api)

    // The version compiled against is declared as a plain `require` in the published POM; the host
    // pins its own SDK `strictly`. See AGENTS.md, "The SDK edge".
    implementation(libs.matrix.sdk)
    // uniffi bindings runtime.
    implementation(variantOf(libs.jna) { artifactType("aar") })

    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)

    testCommonDependencies(libs)
    testImplementation(projects.call.test)
}
