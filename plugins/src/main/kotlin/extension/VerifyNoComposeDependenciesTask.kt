/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package extension

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Fails when any `androidx.compose.*` artifact reaches the runtime classpath of a module that must
 * stay free of Compose: the call lifecycle has to work with no UI attached (spec rule R8), and that
 * is enforced here as a Gradle fact rather than a review comment. A transitive leak (an
 * `activity-compose` pulled in by a dependency) fails `check` exactly like a direct one.
 *
 * Reads the resolution result through a [ResolvedComponentResult] provider, the configuration-cache
 * compatible way of resolving a configuration in a task.
 */
abstract class VerifyNoComposeDependenciesTask : DefaultTask() {
    @get:Input
    abstract val rootComponent: Property<ResolvedComponentResult>

    /** For the failure message; captured at configuration time, since `project` is off limits at execution. */
    @get:Input
    abstract val projectPath: Property<String>

    @TaskAction
    fun verify() {
        val offenders = mutableSetOf<String>()
        val visited = mutableSetOf<ResolvedComponentResult>()
        fun visit(component: ResolvedComponentResult) {
            if (!visited.add(component)) return
            component.dependencies
                .filterIsInstance<ResolvedDependencyResult>()
                .forEach { dependency ->
                    val selected = dependency.selected
                    val module = selected.moduleVersion
                    val group = module?.group.orEmpty()
                    if (group.startsWith("androidx.compose") && "$group:${module?.name}" !in ANNOTATION_ONLY_ARTIFACTS) {
                        offenders.add(module.toString())
                    }
                    visit(selected)
                }
        }
        visit(rootComponent.get())
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "${projectPath.get()} must not depend on Compose, but its runtime classpath contains:\n" +
                    offenders.sorted().joinToString("\n") { " - $it" }
            )
        }
    }

    private companion object {
        /**
         * `@Stable` and `@Immutable` and nothing else: no runtime, no compiler plugin. `androidx.activity`
         * reaches it through `androidx.navigationevent`, and every host that draws anything already has it.
         * Letting it through keeps the check about Compose rather than about a marker jar.
         */
        val ANNOTATION_ONLY_ARTIFACTS = setOf(
            "androidx.compose.runtime:runtime-annotation",
            "androidx.compose.runtime:runtime-annotation-android",
        )
    }
}
