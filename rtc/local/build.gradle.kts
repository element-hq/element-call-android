/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

import java.security.MessageDigest

// The matrix-rust-rtc core as an AAR file: `./tools/rtc/fetch-rust-rtc` drops the pinned release here and
// `./tools/rtc/build-rust-rtc` a local build (both gitignored), and this bare project publishes the file on
// its `default` configuration so that call/impl and call/ui resolve the same one. See docs/local_stack.md,
// layer 1.
//
// Temporary: the core has no Maven coordinate yet. When it publishes one, this project goes and the
// consumers switch to a catalog entry.
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

configurations.maybeCreate("default")
artifacts.add("default", aar) {
    type = "aar"
    extension = "aar"
}

// The AAR under the coordinate the plan reserves for the core's own publication (§2.3), so that the
// published POMs of call/impl and call/ui name a real dependency rather than this project. Reaches the
// LOCAL Maven repository only: this project has no remote repository configured, and RELEASING.md says
// why it never gets one. The day the core publishes, the coordinate stays and this block goes.
group = "org.matrix.rtc"
version = providers.gradleProperty("MATRIX_RTC_VERSION").get()

publishing {
    publications {
        create<MavenPublication>("aar") {
            artifactId = "matrixrtc-android"
            artifact(aar) {
                extension = "aar"
            }
            pom {
                name.set("matrix-rust-rtc for Android (local build)")
                description.set(
                    "The matrix-rust-rtc AAR in rtc/local, published to the local Maven repository by element-call-android " +
                        "until the core publishes its own artifact. Not for distribution."
                )
                packaging = "aar"
            }
        }
    }
}
