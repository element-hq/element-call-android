import extension.testCommonDependencies

/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

// element-call-matrix: the turnkey Matrix transport over the Rust SDK, and the temporary
// widget-driver stopgap. The ONLY module importing org.matrix.rustcomponents.sdk. No Compose.
plugins {
    id("io.element.call.android-library")
    id("io.element.call.no-compose")
    id("io.element.call.publish")
}

android {
    namespace = "io.element.android.call.matrix"

    defaultConfig {
        // What a minifying host must keep: JNA and the SDK's uniffi bindings (§8.3).
        consumerProguardFiles("consumer-rules.pro")
    }
}

// The SDK edge (AGENTS.md, sdk-compat.yml). `-PmatrixSdkVersion=<v>` compiles and tests this module
// against another SDK release; `-PmatrixSdkTestVersion=<v>` keeps the compile pin and forces the newer
// SDK onto the unit test runtime classpath only, which is Element X's real situation the month after it
// bumps: our bytecode, their SDK. SdkSurfaceBinaryCompatibilityTest is what fails there.
providers.gradleProperty("matrixSdkVersion").orNull?.let { sdkVersion ->
    configurations.configureEach {
        resolutionStrategy.force("org.matrix.rustcomponents:sdk-android:$sdkVersion")
    }
}
providers.gradleProperty("matrixSdkTestVersion").orNull?.let { sdkVersion ->
    configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
        resolutionStrategy.force("org.matrix.rustcomponents:sdk-android:$sdkVersion")
    }
}

dependencies {
    api(projects.call.api)

    // The version compiled against is declared as a plain `require` in the published POM; the host
    // pins its own SDK `strictly`. See AGENTS.md, "The SDK edge".
    implementation(libs.matrix.sdk)
    // uniffi bindings runtime.
    implementation(variantOf(libs.jna) { artifactType("aar") })

    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)

    testCommonDependencies(libs)
    testImplementation(projects.call.test)
}
