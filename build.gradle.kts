import org.gradle.accessors.dm.LibrariesForLibs

/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("io.element.call.root")
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.dependencyanalysis)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

tasks.register<Delete>("clean").configure {
    delete(rootProject.layout.buildDirectory)
}

private val catalog = the<LibrariesForLibs>()

allprojects {
    // Detekt
    apply {
        plugin("io.gitlab.arturbosch.detekt")
    }
    detekt {
        // preconfigure defaults
        buildUponDefaultConfig = true
        // activate all available (even unstable) rules.
        allRules = true
        // point to your custom config defining rules to run, overwriting default behavior
        config.from(files("$rootDir/tools/detekt/detekt.yml"))
    }
    dependencies {
        detektPlugins(catalog.detekt.compose.rules)
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        exclude("io/element/android/call/tests/konsist/failures/**")
    }

    // KtLint
    apply {
        plugin("org.jlleitschuh.gradle.ktlint")
    }

    // See https://github.com/JLLeitschuh/ktlint-gradle#configuration
    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version = catalog.versions.ktlint.get()
        android = true
        ignoreFailures = false
        enableExperimentalRules = true
        // display the corresponding rule
        verbose = true
        reporters {
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
            // To have XML report for the CI to annotate the PR, see .github/workflows/quality.yml
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
        }
        val generatedPath = "${layout.buildDirectory.asFile.get()}/generated/"
        filter {
            exclude { element -> element.file.path.contains(generatedPath) }
            exclude("io/element/android/call/tests/konsist/failures/**")
        }
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            // Warnings are potential errors, so stop ignoring them
            // This is disabled by default, but the CI will enforce this.
            // You can override by passing `-PallWarningsAsErrors=true` in the command line
            // Or add a line with "allWarningsAsErrors=true" in your ~/.gradle/gradle.properties file
            allWarningsAsErrors = findProperty("allWarningsAsErrors") == "true"

            // Fix compilation warning for annotations
            // See https://youtrack.jetbrains.com/issue/KT-73255/Change-defaulting-rule-for-annotations for more details
            freeCompilerArgs.add("-Xannotation-default-target=first-only")
        }
    }
}

// See https://github.com/autonomousapps/dependency-analysis-android-gradle-plugin/wiki/Customizing-plugin-behavior
dependencyAnalysis {
    issues {
        all {
            onUnusedDependencies {
                exclude("com.jakewharton.timber:timber")
                // Runtime of the uniffi bindings: nothing in this repository references JNA directly.
                exclude("net.java.dev.jna:jna")
            }
            onUnusedAnnotationProcessors {}
            onRedundantPlugins {}
            onIncorrectConfiguration {}
        }
    }
}

allprojects {
    tasks.withType<Test> {
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)

        val isScreenshotTest = project.gradle.startParameter.taskNames.any { it.contains("paparazzi", ignoreCase = true) }
        if (isScreenshotTest) {
            // Increase heap size for screenshot tests
            maxHeapSize = "2g"
        } else {
            // Disable screenshot tests by default
            exclude("ui/*.class")
        }
    }
}

// Register quality check tasks.
tasks.register("runQualityChecks") {
    dependsOn(":tests:konsist:testDebugUnitTest")
    project.subprojects {
        tasks.findByPath("$path:lintDebug")?.let { dependsOn(it) }
        tasks.findByName("detekt")?.let { dependsOn(it) }
        tasks.findByName("ktlintCheck")?.let { dependsOn(it) }
        tasks.findByName("verifyNoComposeDependencies")?.let { dependsOn(it) }
    }
    dependsOn("checkDocs")
    // Make sure all checks run even if some fail
    gradle.startParameter.isContinueOnFailure = true
}

// Register Markdown documentation check task.
tasks.register("checkDocs", Exec::class.java) {
    inputs.files("./*.md", "docs/**/*.md")
    commandLine("python3", "tools/docs/generate_toc.py", "--verify", *inputs.files.map { it.path }.toTypedArray())
}

// Register Markdown documentation TOC generation task.
tasks.register("generateDocsToc", Exec::class.java) {
    inputs.files("./*.md", "docs/**/*.md")
    commandLine("python3", "tools/docs/generate_toc.py", *inputs.files.map { it.path }.toTypedArray())
}

// Make sure to delete old screenshots before recording new ones
subprojects {
    val snapshotsDir = File("${project.projectDir}/src/test/snapshots")
    val removeOldScreenshotsTask = tasks.register("removeOldSnapshots") {
        onlyIf { snapshotsDir.exists() }
        doFirst {
            println("Delete previous screenshots located at $snapshotsDir\n")
            snapshotsDir.deleteRecursively()
        }
    }
    tasks.findByName("recordPaparazzi")?.dependsOn(removeOldScreenshotsTask)
    tasks.findByName("recordPaparazziDebug")?.dependsOn(removeOldScreenshotsTask)
    tasks.findByName("recordPaparazziRelease")?.dependsOn(removeOldScreenshotsTask)
}
