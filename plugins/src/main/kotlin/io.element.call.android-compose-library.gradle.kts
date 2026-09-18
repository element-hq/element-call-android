/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

/**
 * This will generate the plugin "io.element.call.android-compose-library", used in android library with compose modules.
 */
import extension.androidLibraryConfig
import extension.commonDependencies
import extension.composeDependencies
import extension.composeLibraryConfig
import extension.setupKover
import org.gradle.accessors.dm.LibrariesForLibs

val libs = the<LibrariesForLibs>()
plugins {
    id("com.android.library")
    id("com.autonomousapps.dependency-analysis")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    androidLibraryConfig(project)
    composeLibraryConfig()
    // No core library desugaring: see io.element.call.android-library.
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
    composeDependencies(libs)
}
