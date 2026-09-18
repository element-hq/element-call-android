/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

/**
 * This will generate the plugin "io.element.call.android-compose-application", used by the sample app.
 */
import extension.androidAppConfig
import extension.commonDependencies
import extension.composeAppConfig
import extension.composeDependencies
import extension.setupKover
import org.gradle.accessors.dm.LibrariesForLibs

val libs = the<LibrariesForLibs>()
plugins {
    id("com.android.application")
    id("com.autonomousapps.dependency-analysis")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    androidAppConfig(project)
    composeAppConfig()
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
    composeDependencies(libs)
    coreLibraryDesugaring(libs.android.desugar)
}
