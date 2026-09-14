import extension.testCommonDependencies

/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

// element-call-ui: the ONLY Compose module. Screen, tiles, layout, minimized bar, floating tile,
// picture-in-picture content, the style port and the previews.
plugins {
    id("io.element.call.android-compose-library")
}

android {
    namespace = "io.element.android.call.ui"

    testOptions {
        unitTests {
            // The Robolectric Compose tests need the merged manifest (for the test Activity) and the
            // module's strings, the way Element X's view tests do.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    api(projects.call.api)

    // For libwebrtc's renderer classes only, in the ui.video package: the texture view draws I420
    // frames with EglRenderer, which ships in the RTC AAR's nested libwebrtc.jar. Nothing here touches
    // the RTC core.
    implementation(projects.rtc.local)
    implementation(libs.androidx.corektx)

    testCommonDependencies(libs, includeTestComposeView = true)
    testImplementation(projects.call.test)
}
