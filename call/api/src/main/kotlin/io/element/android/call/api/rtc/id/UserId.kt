/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc.id

import java.io.Serializable

/**
 * A [String] holding a valid Matrix user ID.
 *
 * The library's own type, so that a host is never asked to hand over Element X's. Element X maps
 * its `UserId` to this one at the port. Validated on construction: an id that fails the grammar is a
 * bug on whichever side built it, and cheaper to see here than as a member nobody can match.
 *
 * https://spec.matrix.org/latest/appendices/#user-identifiers
 */
@JvmInline
value class UserId(val value: String) : Serializable {
    init {
        require(MatrixPatterns.isUserId(value)) { "`$value` is not a valid user id.\nExample user id: `@name:domain`." }
    }

    override fun toString(): String = value

    val extractedDisplayName: String
        get() = value
            .removePrefix("@")
            .substringBefore(":")

    val domainName: String?
        get() = value.substringAfter(":").takeIf { it.isNotEmpty() }
}
