/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.api.auth

/**
 * An OpenID token used to prove our Matrix identity to a third party service, such as a MatrixRTC
 * transport authorising us against a SFU.
 */
data class MatrixOpenIdToken(
    val accessToken: String,
    val tokenType: String,
    val matrixServerName: String,
    val expiresInSeconds: Long,
)
