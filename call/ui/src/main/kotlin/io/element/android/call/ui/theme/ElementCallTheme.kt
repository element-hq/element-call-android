/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember

/**
 * Provides a style to the call composables underneath. Without it they draw with the defaults, so the
 * wrapper is only needed by a host that wants its own design system in the call.
 *
 * @param style null builds the default style: Compound's dark tokens, the bundled glyphs, initials avatars.
 * @param content the call composables to style.
 */
@Composable
fun ElementCallTheme(
    style: ElementCallStyle? = null,
    content: @Composable () -> Unit,
) {
    val resolved = style ?: rememberDefaultElementCallStyle()
    CompositionLocalProvider(LocalElementCallStyle provides resolved, content = content)
}

@Composable
private fun rememberDefaultElementCallStyle(): ElementCallStyle {
    val icons = ElementCallIcons.default()
    return remember(icons) {
        ElementCallStyle(
            colors = ElementCallColors.Dark,
            typography = ElementCallTypography.Default,
            icons = icons,
            avatar = { data, size -> DefaultElementCallAvatar(data = data, size = size) },
        )
    }
}

/**
 * How the call composables reach the style: the provided one, else the defaults. Reading through here
 * rather than capturing a value is what lets a host change the style under a running call.
 */
object ElementCallTheme {
    val style: ElementCallStyle
        @Composable
        get() = LocalElementCallStyle.current ?: rememberDefaultElementCallStyle()

    val colors: ElementCallColors
        @Composable
        @ReadOnlyComposable
        get() = LocalElementCallStyle.current?.colors ?: ElementCallColors.Dark

    val typography: ElementCallTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalElementCallStyle.current?.typography ?: ElementCallTypography.Default

    val icons: ElementCallIcons
        @Composable
        get() = LocalElementCallStyle.current?.icons ?: ElementCallIcons.default()
}
