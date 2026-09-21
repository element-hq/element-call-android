/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

pluginManagement {
    repositories {
        includeBuild("plugins")
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // The matrix-rust-rtc core, io.element.android:matrix-rtc-android, as the file in rtc/local: the
        // release gradle.properties pins (./tools/rtc/fetch-rust-rtc) or a local build (./tools/rtc/build-rust-rtc).
        // The pattern names no version on purpose - whatever file is there is the core, and rtc/local
        // warns when it is not the pinned release. Artifact-only metadata, hence the `aar` selector on
        // every dependency (the catalog's `matrix_rtc_android`). A host resolves the same coordinate the
        // same way from the core's GitHub release (README, "Consuming a release"). See docs/local_stack.md.
        ivy {
            url = settingsDir.resolve("rtc/local").toURI()
            patternLayout { artifact("matrixrtc-release.[ext]") }
            metadataSources { artifact() }
            content { includeModule("io.element.android", "matrix-rtc-android") }
        }
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Distinct from Element X's "ElementX": both roots are loaded when this repository is included in
// Element X as a composite build (see docs/local_stack.md, layer 2).
rootProject.name = "ElementCallAndroid"

// The published library: five artifacts, one version.
include(":call:api")
include(":call:impl")
include(":call:ui")
include(":call:matrix")
include(":call:test")

// The matrix-rust-rtc AAR: checks the file above and publishes it to the local Maven repository for
// tests/consumer and a host on layer 3 (see docs/local_stack.md).
include(":rtc:local")

// One version for the five artifacts.
include(":bom")

// Not published.
include(":tests:konsist")
include(":tests:testutils")
include(":tests:uitests")

// The harness: a Compose app over the fakes (docs/README.md). Not published.
include(":sample")
