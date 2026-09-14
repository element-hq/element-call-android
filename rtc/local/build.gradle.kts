/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

// The matrix-rust-rtc core, as a locally built AAR: `./tools/rtc/build-rust-rtc` drops it here
// (gitignored), and this bare project publishes it on its `default` configuration so that call/impl
// and call/ui resolve the same file. See docs/local_stack.md, layer 1.
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
            "Run ./tools/rtc/build-rust-rtc or copy an AAR in place (docs/local_stack.md, layer 1).\n"
    )
}
// The AAR's `proguard.txt` is a C-style `/* ... */` comment, which ProGuard syntax does not have, and R8
// refuses the whole build of any minified host that consumes it (tests/consumer found this; it is core
// feedback in docs/FEEDBACK.md). Repackaged without it, for the local build and the publication alike;
// call/impl and call/matrix ship the consumer rules the core needs.
val usableAar = tasks.register<Zip>("stripInvalidProguardRules") {
    from(zipTree(aar)) {
        exclude("proguard.txt")
    }
    // The extension is what tells AGP this is an AAR, not a zip; archiveFileName alone leaves it "zip".
    archiveBaseName.set("matrixrtc-android")
    archiveExtension.set("aar")
    destinationDirectory.set(layout.buildDirectory.dir("aar"))
}

configurations.maybeCreate("default")
artifacts.add("default", usableAar)

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
            artifact(usableAar) {
                extension = "aar"
            }
            pom {
                name.set("matrix-rust-rtc for Android (local build)")
                description.set(
                    "A locally built matrix-rust-rtc AAR, published to the local Maven repository by element-call-android " +
                        "until the core publishes its own artifact. Not for distribution."
                )
                packaging = "aar"
            }
        }
    }
}
