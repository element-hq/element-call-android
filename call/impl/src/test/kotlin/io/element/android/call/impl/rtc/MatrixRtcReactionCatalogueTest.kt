/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import com.google.common.truth.Truth.assertThat
import io.element.android.call.impl.rtc.MatrixRtcReactionCatalogue.Kind
import io.element.android.call.impl.rtc.MatrixRtcReactionCatalogue.Resolved
import org.junit.Test

class MatrixRtcReactionCatalogueTest {
    private val catalogue = MatrixRtcReactionCatalogue(
        listOf(
            Kind("thumbsup", "👍", null),
            Kind("clapping", "👏", "clap"),
            Kind("drum", "🥁", "baduntss"),
        )
    )

    @Test
    fun `a known name plays its own sound`() {
        assertThat(catalogue.resolve("clapping", "👏")).isEqualTo(Resolved("👏", "clap"))
        assertThat(catalogue.resolve("drum", "🥁")).isEqualTo(Resolved("🥁", "baduntss"))
    }

    @Test
    fun `a known name the core calls silent plays web's generic sound`() {
        assertThat(catalogue.resolve("thumbsup", "👍")).isEqualTo(Resolved("👍", "generic"))
    }

    @Test
    fun `the bell is known though the core does not have it`() {
        assertThat(catalogue.resolve("bell", "🛎")).isEqualTo(Resolved("🛎️", "bell"))
    }

    @Test
    fun `a known name shows the catalogue's emoji whatever was sent`() {
        assertThat(catalogue.resolve("clapping", "🙃").emoji).isEqualTo("👏")
    }

    @Test
    fun `an unknown or empty name shows what was sent with the generic sound`() {
        assertThat(catalogue.resolve("unicorn", "🦄")).isEqualTo(Resolved("🦄", "generic"))
        assertThat(catalogue.resolve("", "🦄")).isEqualTo(Resolved("🦄", "generic"))
    }

    @Test
    fun `the core's answer wins once it has the bell`() {
        val answered = MatrixRtcReactionCatalogue(listOf(Kind("bell", "🔔", "bell")))
        assertThat(answered.resolve("bell", "🛎️").emoji).isEqualTo("🔔")
    }
}
