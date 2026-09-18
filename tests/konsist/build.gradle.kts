/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

plugins {
    id("io.element.call.android-compose-library")
}

android {
    namespace = "io.element.android.call.tests.konsist"
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    testImplementation(composeBom)
    testImplementation(libs.androidx.compose.ui.tooling.preview)
    testImplementation(libs.test.junit)
    testImplementation(libs.test.konsist)
    testImplementation(libs.test.truth)
    testImplementation(projects.call.ui)
}

// Make sure Konsist tests run for 'check' tasks. This is needed because otherwise we'd have to either:
// - Add every single module as a dependency of this one.
// - Move the Konsist tests to another module, which does not need to know about Konsist.
tasks.withType<Test>().configureEach {
    // Disable parallel tests: every time we run a test class in parallel a new JVM is started, and the `KoScope` contents can't be shared between them.
    // This scope can take a while to load, so having several instances of this happening is a terrible idea.
    // It's actually faster to run the tests sequentially, at least locally.
    maxParallelForks = 1

    val isNotCheckTask = gradle.startParameter.taskNames.any { it.contains("check", ignoreCase = true).not() }
    outputs.upToDateWhen { isNotCheckTask }
}
