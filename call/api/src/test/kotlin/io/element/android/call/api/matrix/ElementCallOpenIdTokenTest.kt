/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.matrix

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ElementCallOpenIdTokenTest {
    @Test
    fun `toString never prints the access token`() {
        val token = ElementCallOpenIdToken(
            accessToken = "secret-bearer-token",
            tokenType = "Bearer",
            matrixServerName = "example.org",
            expiresInSeconds = 3600,
        )

        val printed = token.toString()

        assertThat(printed).doesNotContain("secret-bearer-token")
        assertThat(printed).contains("accessToken=<redacted>")
        assertThat(printed).contains("tokenType=Bearer")
        assertThat(printed).contains("matrixServerName=example.org")
        assertThat(printed).contains("expiresInSeconds=3600")
    }

    @Test
    fun `equality still covers the access token`() {
        val a = ElementCallOpenIdToken("one", "Bearer", "example.org", 3600)
        val b = ElementCallOpenIdToken("two", "Bearer", "example.org", 3600)

        assertThat(a).isNotEqualTo(b)
        assertThat(a.accessToken).isEqualTo("one")
    }
}
