/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import io.element.android.call.api.matrix.ElementCallMatrixTransport
import org.matrix.rtc.FfiOpenIdToken
import org.matrix.rtc.OpenIdTokenProvider

/**
 * Proves our Matrix identity to the transport's authorisation service, which exchanges the token
 * for SFU credentials.
 */
internal class RustOpenIdTokenProvider(
    private val transport: ElementCallMatrixTransport,
) : OpenIdTokenProvider {
    override suspend fun getOpenIdToken(): FfiOpenIdToken {
        val token = transport.getOpenIdToken().getOrThrow()
        return FfiOpenIdToken(
            accessToken = token.accessToken,
            tokenType = token.tokenType,
            matrixServerName = token.matrixServerName,
            expiresInSecs = token.expiresInSeconds.toULong(),
        )
    }
}
