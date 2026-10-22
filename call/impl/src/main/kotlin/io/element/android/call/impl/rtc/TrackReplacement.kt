/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * [read] once, and again each time [replacements] moves, cancelling the reader of the old track: that one never yields
 * again (feature-hq 034 C6).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> readFollowingReplacements(replacements: StateFlow<Int>, read: () -> Flow<T>): Flow<T> =
    replacements.flatMapLatest { read() }
