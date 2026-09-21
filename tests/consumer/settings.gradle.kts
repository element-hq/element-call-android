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
        val distDir = providers.gradleProperty("elementCallDistDir").orNull
        if (distDir == null) {
            // Only the library and the locally published core come from here; everything else from the
            // repositories a host would have anyway.
            mavenLocal {
                content {
                    includeGroup("io.element.android")
                }
            }
        } else {
            // The release assets as scripts/release.sh lays them out - one flat directory - resolved exactly
            // the way a host resolves a GitHub release (README, "Consuming a release"): the library through
            // its module metadata, the core from its own release, artifact only. Proves the layout before
            // a tag is pushed.
            ivy {
                url = File(distDir).toURI()
                patternLayout { artifact("[artifact]-[revision](-[classifier]).[ext]") }
                metadataSources { gradleMetadata() }
                content {
                    includeModule("io.element.android", "element-call-bom")
                    includeModule("io.element.android", "element-call-api")
                    includeModule("io.element.android", "element-call")
                    includeModule("io.element.android", "element-call-ui")
                    includeModule("io.element.android", "element-call-matrix")
                    includeModule("io.element.android", "element-call-test")
                }
            }
            ivy {
                url = uri("https://github.com/element-hq/matrix-rust-rtc/releases/download")
                patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
                metadataSources { artifact() }
                content { includeModule("io.element.android", "matrix-rtc-android") }
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
