import extension.testCommonDependencies

/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

// element-call: the stack, the controller, the foreground service, the receiver, audio routing, and
// the Rust core wrapper (package io.element.android.call.impl.rtc), which is the ONLY place that
// imports org.matrix.rtc. No Compose.
plugins {
    id("io.element.call.android-library")
    id("io.element.call.no-compose")
    id("io.element.call.publish")
}

android {
    namespace = "io.element.android.call.impl"

    defaultConfig {
        // What a minifying host must keep: JNA, the uniffi bindings, libwebrtc's JNI entry points (§8.3).
        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions {
        unitTests {
            // The picture-in-picture tests read the actions' strings.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    api(projects.call.api)

    // The matrix-rust-rtc core: the file in rtc/local here, the release asset for a host (see docs/local_stack.md).
    implementation(variantOf(libs.matrix.rtc.android) { artifactType("aar") })
    // uniffi bindings runtime.
    implementation(variantOf(libs.jna) { artifactType("aar") })

    implementation(libs.androidx.corektx)
    // For ElementCallPictureInPicture, which binds to the host's Activity. `activity`, never `activity-compose`.
    implementation(libs.androidx.activity.activity)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.serialization.json)

    testCommonDependencies(libs)
    testImplementation(projects.call.test)
}
