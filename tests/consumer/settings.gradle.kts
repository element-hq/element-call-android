/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

// A separate Gradle build, on purpose: it consumes the library the way a host does, from Maven
// artifacts rather than from source, so it exercises the real POMs and module metadata, the consumer
// rules, the manifest merger and the native-library packaging that the composite build hides
// (plan §8.4). Run from the repository root after publishing:
//
//   ./gradlew publishToMavenLocal -PVERSION_NAME=0.0.0-ci
//   ./gradlew -p tests/consumer :app:assembleRelease -PelementCallVersion=0.0.0-ci
//
// or, with -PelementCallDistDir=/abs/path, against a directory of release assets instead of ~/.m2
// (scripts/release.sh does this with what it is about to attach to the GitHub release).
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val elementCallModules = listOf("element-call-bom", "element-call-api", "element-call", "element-call-ui", "element-call-test")
        val distDir = providers.gradleProperty("elementCallDistDir").orNull
        if (distDir == null) {
            // Only the library comes from here, and the core when it is a local build (-PmatrixRtcLocalVersion);
            // everything else from the repositories a host would have anyway.
            mavenLocal {
                content {
                    elementCallModules.forEach { includeModule("io.element.android", it) }
                    if (providers.gradleProperty("matrixRtcLocalVersion").isPresent) {
                        includeModule("io.element.android", "matrix-rtc-android")
                    }
                }
            }
        } else {
            // The release assets as scripts/release.sh lays them out - one flat directory - resolved exactly
            // the way a host resolves a GitHub release instead of Maven Central (README, "Consuming a release").
            // Proves the layout before a tag is pushed.
            ivy {
                url = File(distDir).toURI()
                patternLayout { artifact("[artifact]-[revision](-[classifier]).[ext]") }
                metadataSources { gradleMetadata() }
                content {
                    elementCallModules.forEach { includeModule("io.element.android", it) }
                }
            }
        }
        google()
        mavenCentral()
    }
    versionCatalogs {
        // The library's own catalog, so the consumer builds with the same AGP, Kotlin and Compose.
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "ElementCallConsumer"

include(":app")
