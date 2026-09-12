/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

/**
 * This will generate the plugin "io.element.call.no-compose", applied on top of
 * "io.element.call.android-library" by the modules that must stay free of Compose (call/api,
 * call/impl, call/matrix). It registers `verifyNoComposeDependencies` and wires it into `check`.
 */
import extension.VerifyNoComposeDependenciesTask

val verifyNoComposeDependencies = tasks.register<VerifyNoComposeDependenciesTask>("verifyNoComposeDependencies") {
    group = "verification"
    description = "Fails if any androidx.compose artifact is on the release runtime classpath."
    rootComponent.set(
        configurations.named("releaseRuntimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }
    )
}

tasks.named("check") {
    dependsOn(verifyNoComposeDependencies)
}
