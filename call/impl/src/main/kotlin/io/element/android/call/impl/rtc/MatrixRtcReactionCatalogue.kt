/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import io.element.android.call.api.rtc.MatrixRtcReactionKind
import org.matrix.rtc.reactionCatalog

/** The core's catalogue, in the order its picker shows them (feature-hq 009 R11). */
internal fun coreReactionCatalogue(): List<MatrixRtcReactionKind> = reactionCatalog().map { MatrixRtcReactionKind(name = it.name, emoji = it.emoji) }
