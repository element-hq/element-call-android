/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.tests.konsist

import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.google.common.truth.Truth.assertThat
import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withAllAnnotationsOf
import com.lemonappdev.konsist.api.ext.list.withName
import com.lemonappdev.konsist.api.ext.list.withoutName
import com.lemonappdev.konsist.api.verify.assertEmpty
import com.lemonappdev.konsist.api.verify.assertTrue
import io.element.android.call.ui.preview.PreviewsDayNight
import org.junit.Test

class KonsistPreviewTest {
    @Test
    fun `Functions with '@PreviewsDayNight' annotation should have 'Preview' suffix`() {
        Konsist
            .scopeFromProject()
            .functions()
            .withAllAnnotationsOf(PreviewsDayNight::class)
            .assertTrue {
                it.hasNameEndingWith("Preview") &&
                    it.hasNameEndingWith("LightPreview").not() &&
                    it.hasNameEndingWith("DarkPreview").not() &&
                    it.hasNameEndingWith("BlackPreview").not()
            }
    }

    @Test
    fun `Functions with '@PreviewsDayNight' annotation should contain 'ElementCallPreview' composable`() {
        Konsist
            .scopeFromProject()
            .functions()
            .withAllAnnotationsOf(PreviewsDayNight::class)
            .assertTrue {
                it.text.contains("ElementCallPreview")
            }
    }

    @Test
    fun `Functions with '@PreviewsDayNight' are internal`() {
        Konsist
            .scopeFromProject()
            .functions()
            .withAllAnnotationsOf(PreviewsDayNight::class)
            .assertTrue {
                it.hasInternalModifier
            }
    }

    /**
     * Multiple previews of the same view: add the extra names here, sorted.
     */
    private val previewNameExceptions = emptyList<String>()

    @Test
    fun `previewNameExceptions is sorted alphabetically`() {
        assertThat(previewNameExceptions.sorted()).isEqualTo(previewNameExceptions)
    }

    @Test
    fun `previewNameExceptions only contains existing functions`() {
        val names = previewNameExceptions
            .toMutableSet()
        Konsist
            .scopeFromProject()
            .functions()
            .withAllAnnotationsOf(PreviewsDayNight::class)
            .withName(previewNameExceptions)
            .let {
                it.forEach { function ->
                    names.remove(function.name)
                }
            }
        assertThat(names).isEmpty()
    }

    @Test
    fun `Functions with '@PreviewsDayNight' have correct name`() {
        Konsist
            .scopeFromProject()
            .functions()
            .withAllAnnotationsOf(PreviewsDayNight::class)
            .withoutName(previewNameExceptions)
            .assertTrue(
                additionalMessage = "Functions for Preview should be named like this: <ViewUnderPreview>Preview. " +
                    "Exception can be added to the test, for multiple Previews of the same view",
            ) {
                val testedView = if (it.name.endsWith("RtlPreview")) {
                    it.name.removeSuffix("RtlPreview")
                } else {
                    it.name.removeSuffix("Preview")
                }
                it.name.endsWith("Preview") &&
                    (it.text.contains("$testedView(") ||
                        it.text.contains("$testedView {") ||
                        it.text.contains("ContentToPreview("))
            }
    }

    @Test
    fun `Ensure that '@PreviewLightDark' is not used`() {
        Konsist
            .scopeFromProject()
            .functions()
            .withAllAnnotationsOf(PreviewLightDark::class)
            .assertEmpty(
                additionalMessage = "Use '@PreviewsDayNight' instead of '@PreviewLightDark', or else screenshot(s) will not be generated.",
            )
    }
}
