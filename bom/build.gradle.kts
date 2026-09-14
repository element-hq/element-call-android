/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

// element-call-bom: one version for the five artifacts, so a host writes
// `implementation(platform("io.element.android:element-call-bom:<version>"))` once and names the
// artifacts without versions.
plugins {
    `java-platform`
    id("io.element.call.publish")
}

dependencies {
    constraints {
        api(project(":call:api"))
        api(project(":call:impl"))
        api(project(":call:ui"))
        api(project(":call:matrix"))
        api(project(":call:test"))
    }
}
