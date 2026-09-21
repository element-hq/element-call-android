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
        // Only the library and the locally published core come from here; everything else from the
        // repositories a host would have anyway.
        mavenLocal {
            content {
                includeGroup("io.element.android")
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
