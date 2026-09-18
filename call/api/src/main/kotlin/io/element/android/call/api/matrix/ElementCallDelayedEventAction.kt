/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.matrix

/**
 * What to do with a delayed event the homeserver is still holding on to (MSC4140).
 */
enum class ElementCallDelayedEventAction {
    /** Cancel the delayed event: it will never be sent to the room. */
    CANCEL,

    /** Restart the delay timeout, keeping the event scheduled. */
    RESTART,
}
