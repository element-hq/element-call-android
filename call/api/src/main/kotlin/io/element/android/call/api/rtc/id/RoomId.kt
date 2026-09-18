/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc.id

import java.io.Serializable

/**
 * A [String] holding a valid Matrix room ID. See [UserId] for why the library has its own.
 */
@JvmInline
value class RoomId(val value: String) : Serializable {
    init {
        require(MatrixPatterns.isRoomId(value)) { "`$value` is not a valid room id.\nExample room id: `!room_id:domain`." }
    }

    override fun toString(): String = value
}
