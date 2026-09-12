/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package extension

import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.kotlin.dsl.DependencyHandlerScope
import org.gradle.kotlin.dsl.project

private fun DependencyHandlerScope.implementation(dependency: Any) = dependencies.add("implementation", dependency)
private fun DependencyHandlerScope.testImplementation(dependency: Any) = dependencies.add("testImplementation", dependency)
private fun DependencyHandlerScope.testReleaseImplementation(dependency: Any) = dependencies.add("testReleaseImplementation", dependency)
private fun DependencyHandlerScope.androidTestImplementation(dependency: Any) = dependencies.add("androidTestImplementation", dependency)
private fun DependencyHandlerScope.debugImplementation(dependency: Any) = dependencies.add("debugImplementation", dependency)

/**
 * Dependencies used for unit tests.
 */
fun DependencyHandlerScope.testCommonDependencies(
    libs: LibrariesForLibs,
    includeTestComposeView: Boolean = false,
) {
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.molecule.runtime)
    testImplementation(libs.test.arch.core)
    testImplementation(libs.test.junit)
    testImplementation(libs.test.parameter.injector)
    testImplementation(libs.test.robolectric)
    testImplementation(libs.test.truth)
    testImplementation(libs.test.turbine)
    testImplementation(project(":tests:testutils"))
    if (includeTestComposeView) {
        testImplementation(libs.androidx.compose.ui.test.junit)
        testReleaseImplementation(libs.androidx.compose.ui.test.manifest)
    }
}

/**
 * Dependencies used by all the modules.
 */
fun DependencyHandlerScope.commonDependencies(libs: LibrariesForLibs) {
    implementation(libs.timber)
}

/**
 * Dependencies used by all the modules with composable items.
 */
fun DependencyHandlerScope.composeDependencies(libs: LibrariesForLibs) {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.kotlinx.collections.immutable)
}
