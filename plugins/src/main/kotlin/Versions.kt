/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

import org.gradle.api.JavaVersion
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * The library version itself lives in `gradle.properties` (`VERSION_NAME`), so that a release is a
 * one-line change and can be overridden from the command line for a local publication.
 */
object Versions {
    /**
     * Compile SDK version. Must be updated when a new Android version is released.
     */
    const val COMPILE_SDK = 37

    /**
     * Target SDK version, used by the sample app. Should be kept up to date with COMPILE_SDK.
     */
    const val TARGET_SDK = 37

    /**
     * Minimum SDK version. The matrix-rust-rtc AAR declares 24.
     */
    const val MIN_SDK = 24

    /**
     * JDK used to build. Update this value when you want to use a newer Java version.
     */
    private const val JAVA_VERSION = 21

    /**
     * Bytecode level of the published modules, one JDK behind the toolchain so that a host that has
     * not moved to the toolchain's JDK can still link against the artifacts.
     */
    private const val JVM_TARGET = 17

    val javaVersion: JavaVersion = JavaVersion.toVersion(JVM_TARGET)
    val jvmTarget: JvmTarget = JvmTarget.fromTarget(JVM_TARGET.toString())
    val javaLanguageVersion: JavaLanguageVersion = JavaLanguageVersion.of(JAVA_VERSION)
}
