/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

/**
 * This will generate the plugin "io.element.call.android-library", used in android library without compose modules.
 */
import extension.androidLibraryConfig
import extension.commonDependencies
import extension.setupKover
import org.gradle.accessors.dm.LibrariesForLibs

val libs = the<LibrariesForLibs>()
plugins {
    id("com.android.library")
    id("com.autonomousapps.dependency-analysis")
}

android {
    androidLibraryConfig(project)
    // No core library desugaring, unlike Element X's library plugin: a published AAR built with it
    // requires every host to enable it (AGP checks the AAR metadata), and nothing here needs java.time
    // or streams on minSdk 24. tests/consumer is what caught this.
}

kotlin {
    jvmToolchain {
        languageVersion = Versions.javaLanguageVersion
    }
    compilerOptions {
        jvmTarget = Versions.jvmTarget
    }
}

setupKover()

dependencies {
    commonDependencies(libs)
}
