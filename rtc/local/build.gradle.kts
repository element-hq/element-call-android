/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

import java.security.MessageDigest

// The matrix-rust-rtc core as an AAR file: `./tools/rtc/fetch-rust-rtc` drops the pinned release here and
// `./tools/rtc/build-rust-rtc` a local build (both gitignored). The build resolves the file through the Ivy
// repository over this directory in settings.gradle.kts; this project checks it and publishes it to the
// LOCAL Maven repository under the core's coordinate, so that tests/consumer and a host on layer 3 of
// docs/local_stack.md resolve the dependency the published POMs name.
//
// Temporary: the core is on GitHub Packages (token-gated) and not yet on Maven Central. When it is, this
// project and the Ivy repository go, and the catalog entry resolves from Central like everything else.
plugins {
    `maven-publish`
}

val aar = file("matrixrtc-release.aar")
if (!aar.exists()) {
    logger.warn(
        "\nNote: rtc/local/matrixrtc-release.aar is missing; call/impl and call/ui will not build. " +
            "Run ./tools/rtc/fetch-rust-rtc, or build the core with ./tools/rtc/build-rust-rtc (docs/local_stack.md, layer 1).\n"
    )
} else {
    // Rule 2 of docs/local_stack.md, made automatic: say which core is live when it is not the pinned release.
    val pinned = providers.gradleProperty("MATRIX_RTC_AAR_SHA256").get()
    val actual = MessageDigest.getInstance("SHA-256").digest(aar.readBytes()).joinToString("") { "%02x".format(it) }
    if (actual != pinned) {
        logger.warn(
            "\nNote: rtc/local/matrixrtc-release.aar is not the pinned matrix-rust-rtc release (sha256 $actual): " +
                "a local build is live. ./tools/rtc/fetch-rust-rtc restores the pinned one (docs/local_stack.md, layer 1).\n"
        )
    }
}

// The core's own coordinate (element-hq/matrix-rust-rtc, mobile/android). Reaches the LOCAL Maven
// repository only: this project has no remote repository configured, and RELEASING.md says why it never
// gets one. MATRIX_RTC_VERSION must be the release the pinned URL points at, because the published POMs of
// call/impl and call/ui name it and a host resolves it from that release.
group = "io.element.android"
version = providers.gradleProperty("MATRIX_RTC_VERSION").get()

publishing {
    publications {
        create<MavenPublication>("aar") {
            artifactId = "matrix-rtc-android"
            artifact(aar) {
                extension = "aar"
            }
            pom {
                name.set("matrix-rust-rtc for Android (local copy)")
                description.set(
                    "The matrix-rust-rtc AAR in rtc/local, published to the local Maven repository by element-call-android " +
                        "until the core is on Maven Central. Not for distribution."
                )
                packaging = "aar"
            }
        }
    }
}
