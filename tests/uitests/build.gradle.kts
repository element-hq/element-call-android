/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

// Screenshot tests over every @PreviewsDayNight preview of the library, with Paparazzi. A module of its
// own so that Paparazzi and layoutlib stay off the published modules and the PNGs out of their source
// trees. See docs/screenshot_testing.md.
plugins {
    id("io.element.call.android-compose-library")
    alias(libs.plugins.paparazzi)
}

android {
    // Keep it as short as possible
    namespace = "ui"
}

tasks.withType(Test::class) {
    // Don't fail the test run if there are no tests, this can happen if we run them with screenshot test disabled
    failOnNoDiscoveredTests = false
}

dependencies {
    // Paparazzi 1.3.2 workaround (see https://github.com/cashapp/paparazzi/blob/master/CHANGELOG.md#132---2024-01-13)
    constraints.add("testImplementation", "com.google.guava:guava") {
        attributes {
            attribute(
                TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                objects.named(TargetJvmEnvironment::class.java, TargetJvmEnvironment.STANDARD_JVM)
            )
        }
        because(
            "LayoutLib and sdk-common depend on Guava's -jre published variant." +
                "See https://github.com/cashapp/paparazzi/issues/906."
        )
    }

    // The one module with previews. Its fixtures (*PreviewParam) are in its main source set, so nothing
    // else is needed to render every state.
    implementation(projects.call.ui)

    testImplementation(libs.test.junit)
    testImplementation(libs.test.parameter.injector)
    testImplementation(libs.test.composable.preview.scanner)
}
