/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package extension

import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.GroupingEntityType
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.assign
import org.gradle.kotlin.dsl.configure
import java.io.File

/**
 * Projects that hold no production code, or no Kotlin at all, and are left out of the coverage report.
 */
val excludedKoverSubProjects = listOf(
    ":rtc:local",
    ":bom",
    ":tests:konsist",
    ":tests:testutils",
    ":tests:uitests",
    ":call:test",
    ":sample",
)

private fun Project.kover(any: Any) {
    this.dependencies.add("kover", any)
}

fun Project.setupKover() {
    // If the project is excluded from Kover, don't apply anything
    if (path in excludedKoverSubProjects) return

    // Apply the plugin
    apply(plugin = "org.jetbrains.kotlinx.kover")

    // Create verify all task joining all existing verification tasks
    tasks.register("koverVerifyAll") {
        group = "verification"
        description = "Verifies the code coverage of all subprojects."
        dependsOn(":koverVerifyMerged")
    }
    // https://kotlin.github.io/kotlinx-kover/
    // Run `./gradlew :koverHtmlReportMerged` to get report at ./build/reports/kover
    // Run `./gradlew :koverXmlReportMerged` to get XML report
    extensions.configure<KoverProjectExtension> {
        currentProject {
            createVariant("merged") {
                addWithDependencies("debug", optional = true)
            }
        }

        // If it's the root project, set up kover for subprojects
        if (project.path == ":") {
            for (project in project.subprojects) {
                if (project.path !in excludedKoverSubProjects && File(project.projectDir, "build.gradle.kts").exists()) {
                    kover(project)
                }
            }
        }

        reports {
            filters {
                excludes {
                    classes(
                        // Exclude generated classes.
                        "*ComposableSingletons$*",
                        "*BuildConfig",
                        // Konsist code to make test fails
                        "io.element.android.call.tests.konsist.failures",
                    )
                    annotatedBy(
                        "androidx.compose.ui.tooling.preview.Preview",
                        "io.element.android.call.ui.preview.*",
                    )
                }
            }

            total {
                verify {
                    // General rule: minimum code coverage.
                    rule("Global minimum code coverage.") {
                        groupBy = GroupingEntityType.APPLICATION
                        bound {
                            // Raised once the code is imported from the spike (plan step L2); the
                            // skeleton has nothing to cover.
                            minValue = 0
                            coverageUnits = CoverageUnit.INSTRUCTION
                            aggregationForGroup = AggregationType.COVERED_PERCENTAGE
                        }
                    }
                }
            }
        }
    }
}
