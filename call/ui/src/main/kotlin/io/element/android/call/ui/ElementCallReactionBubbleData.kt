/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.runtime.Immutable
import kotlin.time.Duration

/** One received reaction as a bubble draws it. The name is fixed when it is made, and the bubble keeps it (037 R7a). */
@Immutable
data class ElementCallReactionBubbleData(
    val id: Long,
    val emoji: String,
    /** The sender's display name, or their Matrix ID without one; unused for our own, which reads "You" (R7). */
    val senderName: String,
    val isLocal: Boolean,
    val spread: Duration,
)
