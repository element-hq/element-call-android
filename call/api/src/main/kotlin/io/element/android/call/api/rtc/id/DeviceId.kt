/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc.id

import java.io.Serializable

/**
 * A [String] holding a Matrix device ID. Device ids have no grammar to validate against.
 * See [UserId] for why the library has its own.
 */
@JvmInline
value class DeviceId(val value: String) : Serializable {
    override fun toString(): String = value
}
