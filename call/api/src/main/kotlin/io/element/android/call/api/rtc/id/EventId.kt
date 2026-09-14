/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc.id

import java.io.Serializable

/**
 * A [String] holding a valid Matrix event ID. See [UserId] for why the library has its own.
 */
@JvmInline
value class EventId(val value: String) : Serializable {
    init {
        require(MatrixPatterns.isEventId(value)) {
            "`$value` is not a valid event id.\nExample event id: `\$Rqnc-F-dvnEYJTyHq_iKxU2bZ1CI92-kuZq3a5lr5Zg`."
        }
    }

    override fun toString(): String = value
}
