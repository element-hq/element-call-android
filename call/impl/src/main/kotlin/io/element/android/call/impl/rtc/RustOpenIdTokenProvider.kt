/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrixrtc.impl

import io.element.android.libraries.matrix.api.MatrixClient
import uniffi.matrix_rtc_ffi.FfiOpenIdToken
import uniffi.matrix_rtc_ffi.OpenIdTokenProvider

/**
 * Proves our Matrix identity to the transport's authorisation service, which exchanges the token
 * for SFU credentials.
 */
internal class RustOpenIdTokenProvider(
    private val client: MatrixClient,
) : OpenIdTokenProvider {
    override suspend fun getOpenIdToken(): FfiOpenIdToken {
        val token = client.getOpenIdToken().getOrThrow()
        return FfiOpenIdToken(
            accessToken = token.accessToken,
            tokenType = token.tokenType,
            matrixServerName = token.matrixServerName,
            expiresInSecs = token.expiresInSeconds.toULong(),
        )
    }
}
