/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrixrtc.impl.bridge

import io.element.android.libraries.matrix.api.core.DeviceId
import io.element.android.libraries.matrix.api.core.UserId

/**
 * Cryptographic provenance of an event that arrived encrypted.
 *
 * Prefer [senderId] over any sender claimed in the event JSON: this one is attested, the other is not.
 */
internal data class MatrixRtcEventEncryptionInfo(
    /** The user id this event is cryptographically attested to come from. */
    val senderId: UserId,
    /** The device the event was sent from, as claimed by the sender. */
    val senderDeviceId: DeviceId?,
    /** The curve25519 key of the sending device, when the source reports it. */
    val senderCurve25519Key: String?,
    /**
     * Whether we are willing to vouch for the sender of this event.
     *
     * Not "have I verified this user": a device belonging to an identity we have never verified still
     * counts. False only when the cryptographic story is wrong rather than unconfirmed - a mismatched
     * sender, an identity that changed under us, an unsigned device.
     */
    val isSenderCrossSigned: Boolean,
)
