/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc.id

/**
 * The shapes of the Matrix identifiers this library handles, copied from Element X's `MatrixPatterns`.
 *
 * https://spec.matrix.org/latest/appendices/#identifier-grammar
 */
internal object MatrixPatterns {
    private const val DOMAIN_REGEX = ":[A-Za-z0-9.-]+(:[0-9]{2,5})?"
    private const val BASE_64_ALPHABET = "[0-9A-Za-z/\\+=]+"
    private const val BASE_64_URL_SAFE_ALPHABET = "[0-9A-Za-z/\\-_]+"
    private const val MAX_IDENTIFIER_LENGTH = 255

    private val USER_ID = "^@\\S*?$DOMAIN_REGEX$".toRegex()
    private val ROOM_ID = "^!.+$DOMAIN_REGEX$".toRegex(RegexOption.DOT_MATCHES_ALL)
    private val ROOM_ID_DOMAINLESS = "!$BASE_64_URL_SAFE_ALPHABET".toRegex()
    private val EVENT_ID = "^\\$.+$DOMAIN_REGEX$".toRegex()
    private val EVENT_ID_V3 = "\\$$BASE_64_ALPHABET".toRegex()
    private val EVENT_ID_V4 = "\\$$BASE_64_URL_SAFE_ALPHABET".toRegex()

    fun isUserId(str: String): Boolean {
        return str.length <= MAX_IDENTIFIER_LENGTH && str matches USER_ID
    }

    fun isRoomId(str: String): Boolean {
        return str.length <= MAX_IDENTIFIER_LENGTH && (str matches ROOM_ID_DOMAINLESS || str matches ROOM_ID)
    }

    fun isEventId(str: String): Boolean {
        return str.length <= MAX_IDENTIFIER_LENGTH && (str matches EVENT_ID_V4 || str matches EVENT_ID_V3 || str matches EVENT_ID)
    }
}
