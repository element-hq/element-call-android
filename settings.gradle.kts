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
val matrixRtcLocalVersion: String? = providers.gradleProperty("matrixRtcLocalVersion").orNull

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // The Java half of the Matrix SDK's TLS verifier on Android, for the sample's lab login only. Published by the
        // rustls project in its own repository, not on Maven Central; Element X resolves it the same way.
        exclusiveContent {
            forRepository {
                maven { url = uri("https://github.com/rustls/rustls-platform-verifier/raw/maven-archive/android-release-support/maven/") }
            }
            filter { includeModule("org.rustls", "rustls-platform-verifier") }
        }
        // Nightly SDK bindings, for the sample only: GitHub release assets, resolved as Element X does.
        ivy {
            url = uri("https://github.com/matrix-org/matrix-rust-components-kotlin/releases/download")
            patternLayout { artifact("sdk-[revision]/[artifact]-[revision](-[classifier])(.[ext])") }
            metadataSources { gradleMetadata() }
            content { includeModule("org.matrix.rustcomponents", "sdk-android") }
        }
        // A local build of the matrix-rust-rtc core, opt-in: publish it from matrix-rust-rtc with
        // `./tools/rtc/build-rust-rtc`, then build here with -PmatrixRtcLocalVersion=0.0.0-local (docs/local_stack.md).
        if (matrixRtcLocalVersion != null) {
            logger.lifecycle("Note: resolving io.element.android:matrix-rtc-android $matrixRtcLocalVersion from mavenLocal")
            mavenLocal {
                content { includeModule("io.element.android", "matrix-rtc-android") }
            }
        }
    }
    versionCatalogs {
        create("libs") {
            matrixRtcLocalVersion?.let { version("matrix_rtc", it) }
        }
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Distinct from Element X's "ElementX": both roots are loaded when this repository is included in
// Element X as a composite build (see docs/local_stack.md, layer 2).
rootProject.name = "ElementCallAndroid"

// The published library: four artifacts, one version.
include(":call:api")
include(":call:impl")
include(":call:ui")
include(":call:test")

// One version for the five artifacts.
include(":bom")

// Not published.
include(":tests:konsist")
include(":tests:testutils")
include(":tests:uitests")

// The harness: a Compose app over the fakes (docs/README.md). Not published.
include(":sample")
