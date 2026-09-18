/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp

/**
 * Everything the call composables read from the host's design system: colours, typography, icons and
 * how to draw an avatar. Provided with [ElementCallTheme]; read through [ElementCallTheme.colors] and
 * friends, never captured, so a host that re-brands per session (Element X's enterprise builds) reaches
 * the call screen.
 *
 * Nothing here depends on Compound. Element X builds an instance from its `ElementTheme` colours and
 * typography, `CompoundIcons` and its `Avatar` composable; the sample app and the
 * previews use the defaults, which are Compound's dark tokens as literals.
 */
@Immutable
data class ElementCallStyle(
    val colors: ElementCallColors,
    val typography: ElementCallTypography,
    val icons: ElementCallIcons,
    /** Draws an avatar for a participant at the given size. */
    val avatar: @Composable (ElementCallAvatarData, Dp) -> Unit,
)

/**
 * The style in force, or null where none was provided, in which case the defaults apply. Provide it
 * with [ElementCallTheme] rather than directly.
 */
val LocalElementCallStyle = staticCompositionLocalOf<ElementCallStyle?> { null }
