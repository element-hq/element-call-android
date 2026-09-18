/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui.util

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable

/**
 * The top inset the host's content is drawn under, so the minimized bar can add its own height to
 * it. Copied from Element X's design system, where it is what its scaffolds use.
 */
val scaffoldScrollableContentInsets: WindowInsets
    @Composable
    get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)
