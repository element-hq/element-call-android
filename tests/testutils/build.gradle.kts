/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

// Test utilities copied from Element X's tests/testutils and trimmed: no Appyx node helpers, no
// presenter base, no media fakes. Not published.
plugins {
    id("io.element.call.android-compose-library")
}

android {
    namespace = "io.element.android.call.tests.testutils"
}

dependencies {
    implementation(projects.call.api)
    implementation(libs.test.junit)
    implementation(libs.test.truth)
    implementation(libs.test.robolectric)
    implementation(libs.test.core)
    implementation(libs.test.parameter.injector)
    implementation(libs.coroutines.test)
    implementation(libs.test.turbine)
    implementation(libs.molecule.runtime)
    implementation(libs.androidx.compose.ui.test.junit)
}
