/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

// element-call-test: fakes for every port and for the RTC service, fixtures, the test-pattern frames.
// Published, like Element X's `*/test` modules, so a host's tests need no host.
plugins {
    id("io.element.call.android-library")
    id("io.element.call.publish")
}

android {
    namespace = "io.element.android.call.test"
}

dependencies {
    api(projects.call.api)
}
