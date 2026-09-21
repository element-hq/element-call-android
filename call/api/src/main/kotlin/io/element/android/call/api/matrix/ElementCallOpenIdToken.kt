/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.matrix

/**
 * An OpenID token used to prove our Matrix identity to a third party service, such as a MatrixRTC
 * transport authorising us against a SFU.
 */
data class ElementCallOpenIdToken(
    val accessToken: String,
    val tokenType: String,
    val matrixServerName: String,
    val expiresInSeconds: Long,
) {
    /**
     * Redacted on purpose: a data class prints every field, and this one is a bearer credential. A log line
     * that interpolates the whole object must not leak it.
     */
    override fun toString(): String =
        "ElementCallOpenIdToken(accessToken=<redacted>, tokenType=$tokenType, matrixServerName=$matrixServerName, expiresInSeconds=$expiresInSeconds)"
}
