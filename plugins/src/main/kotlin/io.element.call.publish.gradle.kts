/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavaPlatform
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

/**
 * This will generate the plugin "io.element.call.publish", applied on every published module and on
 * the BOM. One publish command for five artifacts and one version (plan §8.3).
 *
 * Coordinates come from the properties the vanniktech plugin reads: `GROUP` and `VERSION_NAME` in the
 * root `gradle.properties`, `POM_ARTIFACT_ID`, `POM_NAME` and `POM_DESCRIPTION` in each module's own.
 * The target is Maven Central through the Central Portal; until the repository has a remote and CI has
 * run, the only target exercised is `publishToMavenLocal` (docs/local_stack.md, layer 3; RELEASING.md).
 *
 * Signing is off by default so a local publish needs no key. `release.yml` turns it on with
 * `-PRELEASE_SIGNING_ENABLED=true` and in-memory GPG credentials.
 */
plugins {
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("RELEASE_SIGNING_ENABLED").orNull == "true") {
        signAllPublications()
    }

    // An Android library publishes its release variant with a sources jar and an empty javadoc jar,
    // which Central requires; the BOM is a Java platform. The javadoc jar is explicitly empty: the
    // boolean form would apply Dokka and generate documentation for every module in the one daemon,
    // which exhausted CI's 512 MB metaspace on the fourth module for nothing anyone publishes.
    plugins.withId("com.android.library") {
        configure(AndroidSingleVariantLibrary(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources(), variant = "release"))
    }
    plugins.withId("java-platform") {
        configure(JavaPlatform())
    }

    pom {
        // The AGPL licence comes from the POM_LICENCE_* properties; the commercial one is the second.
        licenses {
            license {
                name.set("Element Commercial License")
                url.set("https://raw.githubusercontent.com/element-hq/element-call-android/main/LICENSE-COMMERCIAL")
                distribution.set("repo")
            }
        }
    }
}
