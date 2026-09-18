/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

// The harness (plan §7.4): a Compose app over the fakes, with no server, no login and no camera. A
// picker over the fixture states, a controller that answers the controls, and real frames through
// the real renderer. Not published. The only Activity in this repository lives here.
plugins {
    id("io.element.call.android-compose-application")
}

android {
    namespace = "io.element.android.call.sample"

    defaultConfig {
        applicationId = "io.element.android.call.sample"
        versionCode = 1
        versionName = project.property("VERSION_NAME") as String
    }
}

dependencies {
    implementation(projects.call.ui)
    // ElementCallPictureInPicture, and MatrixRtcNative: the renderer touches libwebrtc natives, so the
    // library has to be loaded even though the sample never opens a call.
    implementation(projects.call.impl)
    // The fixtures and the test pattern. Never call/matrix: the sample has no SDK client.
    implementation(projects.call.test)
    implementation(libs.androidx.activity.activity)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.coroutines.core)

    // The instrumented tests (plan §7.2): the gestures and the pixels only a real device can answer for.
    androidTestImplementation(libs.androidx.compose.ui.test.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.test.core)
    androidTestImplementation(libs.test.runner)
    // Compose's test artifact pulls Espresso 3.5, whose idling breaks on API 36+ (InputManager.getInstance is gone).
    androidTestImplementation(libs.test.espresso.core)
    androidTestImplementation(libs.test.truth)
}
