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
}

dependencies {
    api(projects.call.api)

    testCommonDependencies(libs, includeTestComposeView = true)
}
