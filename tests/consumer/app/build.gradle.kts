/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// A minimal host, minified. It depends on the published artifacts through the BOM, builds the stack
// over the test fakes, draws the overlay and binds picture-in-picture: enough for R8 to walk every
// published module and for the consumer rules to be what keeps the native bindings alive.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val elementCallVersion: String = providers.gradleProperty("elementCallVersion").getOrElse("0.0.0-ci")

android {
    namespace = "io.element.android.call.consumer"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.element.android.call.consumer"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = elementCallVersion
    }

    buildTypes {
        release {
            // The point of this build: the artifacts' consumer rules against R8 in full optimisation.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(platform("io.element.android:element-call-bom:$elementCallVersion"))
    implementation("io.element.android:element-call-ui")
    implementation("io.element.android:element-call")
    implementation("io.element.android:element-call-matrix")
    implementation("io.element.android:element-call-test")

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
}
