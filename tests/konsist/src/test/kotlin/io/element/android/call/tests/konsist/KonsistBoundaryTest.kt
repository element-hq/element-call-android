/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.tests.konsist

import com.google.common.truth.Truth.assertWithMessage
import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.provider.KoAnnotationProvider
import com.lemonappdev.konsist.api.provider.KoNameProvider
import com.lemonappdev.konsist.api.provider.modifier.KoVisibilityModifierProvider
import org.junit.Test

/**
 * The module boundaries of the library, one assertion per rule. Each rule is also a Gradle fact where
 * Gradle can express it (which module depends on what, `verifyNoComposeDependencies`); this test
 * catches what Gradle cannot: which *package* inside a module is allowed to name a foreign type.
 *
 * Plain Truth assertions over file lists rather than Konsist's own, so that an empty list (a rule
 * that nothing exercises yet) is a pass, and an offender is reported by path.
 */
class KonsistBoundaryTest {
    private val files by lazy { Konsist.scopeFromProject().files }
    private val productionFiles by lazy { Konsist.scopeFromProduction().files }

    private val composeModules = setOf("call/ui", "call/test", "sample")

    @Test
    fun `org_matrix_rtc is imported only by the core wrapper in call impl`() {
        files
            .filter { file -> file.imports.any { it.name.startsWith("org.matrix.rtc.") } }
            .filterNot { it.moduleName == "call/impl" && it.hasPackageStartingWith("io.element.android.call.impl.rtc") }
            .assertNoOffender("Only io.element.android.call.impl.rtc may import the matrix-rust-rtc bindings")
    }

    @Test
    fun `the Rust SDK is imported only by call matrix`() {
        files
            .filter { file -> file.imports.any { it.name.startsWith("org.matrix.rustcomponents.sdk") } }
            .filterNot { it.moduleName == "call/matrix" }
            .assertNoOffender("Only call/matrix may import org.matrix.rustcomponents.sdk")
    }

    @Test
    fun `libwebrtc is imported only by the media package of the core wrapper and the video package of the ui`() {
        files
            .filter { file -> file.imports.any { it.name.startsWith("livekit.org.webrtc") } }
            .filterNot {
                it.hasPackageStartingWith("io.element.android.call.impl.rtc.media") ||
                    it.hasPackageStartingWith("io.element.android.call.ui.video")
            }
            .assertNoOffender("Only …call.impl.rtc.media and …call.ui.video may import livekit.org.webrtc")
    }

    @Test
    fun `Compose is imported only by the ui module, the fakes, the sample and the test modules`() {
        files
            .filter { file -> file.imports.any { it.name.startsWith("androidx.compose") } }
            .filterNot { it.moduleName in composeModules || it.moduleName.startsWith("tests/") }
            .assertNoOffender("Compose may not reach the call lifecycle (rule R8): only ${composeModules + "tests/*"} may import androidx.compose")
    }

    @Test
    fun `the core wrapper is not imported outside call impl, except MatrixRtcNative`() {
        files
            .filter { file ->
                file.imports.any {
                    it.name.startsWith("io.element.android.call.impl.rtc.") &&
                        it.name != "io.element.android.call.impl.rtc.MatrixRtcNative"
                }
            }
            .filterNot { it.moduleName == "call/impl" }
            .assertNoOffender("io.element.android.call.impl.rtc is internal to call/impl; only MatrixRtcNative is exposed")
    }

    @Test
    fun `the ui module is not imported by api, impl or matrix`() {
        files
            .filter { file -> file.imports.any { it.name.startsWith("io.element.android.call.ui") } }
            .filter { it.moduleName in setOf("call/api", "call/impl", "call/matrix") }
            .assertNoOffender("call/api, call/impl and call/matrix must not depend on call/ui")
    }

    @Test
    fun `the temporary package of call matrix is confined to its folder and marked as such`() {
        val temporaryPackage = "io.element.android.call.matrix.temporary"
        // The turnkey transport in call/matrix is what wires the stopgap in, so the module itself may
        // import it; nothing outside the module may, so deleting the folder touches one module.
        files
            .filter { file -> file.imports.any { it.name.startsWith("$temporaryPackage.") } }
            .filterNot { it.moduleName == "call/matrix" }
            .assertNoOffender("Nothing outside call/matrix may import from $temporaryPackage")
        files
            .filter { it.hasPackageStartingWith(temporaryPackage) }
            .filterNot { it.path.contains("/temporary/") }
            .assertNoOffender("Files in $temporaryPackage must live under a temporary/ folder")
        productionFiles
            .filter { it.hasPackageStartingWith(temporaryPackage) }
            .flatMap { file -> file.declarations(includeNested = false, includeLocal = false).map { file to it } }
            .filter { (_, declaration) -> declaration is KoNameProvider && declaration is KoAnnotationProvider }
            .filterNot { (_, declaration) -> (declaration as KoAnnotationProvider).hasAnnotationWithName("ElementCallTemporaryApi") }
            .map { (file, declaration) -> "${file.path}: ${(declaration as KoNameProvider).name}" }
            .assertNoOffenderPaths("Every top-level declaration in $temporaryPackage must be annotated @ElementCallTemporaryApi")
    }

    @Test
    fun `no Element X, Metro or Appyx import anywhere`() {
        val forbidden = listOf(
            "io.element.android.features.",
            "io.element.android.libraries.",
            "io.element.android.compound.",
            "dev.zacsweers.metro.",
            "com.bumble.appyx.",
        )
        files
            .filter { file -> file.imports.any { import -> forbidden.any { import.name.startsWith(it) } } }
            .assertNoOffender("The extraction from Element X is complete and stays so: no import of $forbidden")
    }

    @Test
    fun `no Activity outside the sample`() {
        val activityParents = setOf("Activity", "ComponentActivity", "AppCompatActivity", "FragmentActivity")
        productionFiles
            // The sample owns the library's only Activity; tests/consumer is a host, and a host has one.
            .filterNot { it.moduleName == "sample" || it.moduleName.startsWith("tests/") }
            .filter { file -> file.classes(includeNested = true).any { klass -> klass.parents().any { it.name in activityParents } } }
            .assertNoOffender("The library ships composables and a PiP binder, not an Activity (plan decision 1)")
    }

    @Test
    fun `top-level declarations in call impl and call matrix are internal unless allowlisted`() {
        val allowlist = setOf(
            // call/impl
            "ElementCallStack",
            "ElementCallPictureInPicture",
            "DefaultCallAudioDeviceController",
            "DefaultAudioFocus",
            "ElementCallForegroundService",
            "ElementCallActionReceiver",
            "MatrixRtcNative",
            // call/matrix
            "ElementCallSdkTransport",
            "SdkElementCallRoomContext",
        )
        productionFiles
            .filter { it.moduleName == "call/impl" || it.moduleName == "call/matrix" }
            .flatMap { file -> file.declarations(includeNested = false, includeLocal = false).map { file to it } }
            .filter { (_, declaration) -> declaration is KoVisibilityModifierProvider && declaration is KoNameProvider }
            .filter { (_, declaration) -> (declaration as KoVisibilityModifierProvider).hasPublicOrDefaultModifier }
            .filterNot { (_, declaration) -> (declaration as KoNameProvider).name in allowlist }
            .map { (file, declaration) -> "${file.path}: ${(declaration as KoNameProvider).name}" }
            .assertNoOffenderPaths("call/impl and call/matrix expose nothing but the allowlist; make it internal or add it to the list")
    }

    @Test
    fun `runCatching is always runCatchingExceptions`() {
        // Kotlin's runCatching swallows CancellationException; runCatchingExceptions rethrows it.
        val bareRunCatching = Regex("""\brunCatching\s*[({]""")
        files
            .filter { bareRunCatching.containsMatchIn(it.text) }
            .assertNoOffender("Use runCatchingExceptions, which rethrows CancellationException")
    }

    private fun KoFileDeclaration.hasPackageStartingWith(prefix: String): Boolean {
        return packagee?.name?.startsWith(prefix) == true
    }

    private fun List<KoFileDeclaration>.assertNoOffender(message: String) {
        map { it.path }.assertNoOffenderPaths(message)
    }

    private fun List<String>.assertNoOffenderPaths(message: String) {
        assertWithMessage("$message. Offenders:\n${joinToString("\n") { " - $it" }}")
            .that(this)
            .isEmpty()
    }
}
