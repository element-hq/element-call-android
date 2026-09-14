/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.matrix

import io.element.android.call.api.rtc.id.UserId

/**
 * A to-device message received from another device.
 */
data class ElementCallToDeviceMessage(
    val eventType: String,
    /**
     * The user id that *claims* to have sent this message.
     *
     * Unauthenticated on its own. Anything security-relevant must use [encryptionInfo] and its attested
     * [ElementCallEventEncryptionInfo.senderId] instead, and treat a null [encryptionInfo] as untrusted.
     */
    val senderId: UserId,
    /** The message content, as a JSON string. */
    val content: String,
    /** Encryption data, or null if the message arrived in the clear. */
    val encryptionInfo: ElementCallEventEncryptionInfo?,
)
