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

// The locally built matrix-rust-rtc AAR (see docs/local_stack.md, layer 1).
include(":rtc:local")

// Not published.
include(":tests:konsist")
include(":tests:testutils")
include(":tests:uitests")

// The harness: a Compose app over the fakes (docs/README.md). Not published.
include(":sample")
