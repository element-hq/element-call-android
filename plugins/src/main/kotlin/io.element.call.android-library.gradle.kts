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
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
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
    coreLibraryDesugaring(libs.android.desugar)
}
