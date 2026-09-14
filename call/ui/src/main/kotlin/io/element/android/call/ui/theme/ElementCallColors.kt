/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The colours the call composables read. Semantic names, so a host maps its own design system onto
 * them; the defaults are Compound's dark tokens as literals, because the call surface is always dark.
 *
 * Element X provides an instance built from its `ElementTheme` colours inside its own theme, so an
 * enterprise re-brand reaches the call screen without this library depending on Compound.
 */
@Immutable
data class ElementCallColors(
    val textPrimary: Color,
    val textSecondary: Color,
    val textCritical: Color,
    val iconPrimary: Color,
    val iconSecondary: Color,
    val iconCritical: Color,
    val iconAccent: Color,
    /** The call screen's own background. */
    val bgCanvas: Color,
    /** A tile with no video, the floating tile's background. */
    val bgSubtlePrimary: Color,
    /** An inactive control button. */
    val bgSubtleSecondary: Color,
    /** The ring around whoever is talking. */
    val borderActiveSpeaker: Color,
    /** The hairline around our own thumbnail. */
    val borderThumbnail: Color,
    /** The hang-up button. */
    val hangUp: Color,
    /** The screen-sharing indicator in the minimized bar. */
    val sharingAccent: Color,
    /** The "you're sharing your screen" banner. */
    val sharingBanner: Color,
    /** Pills and badges drawn over video, which is not a themed surface. */
    val overlayScrim: Color,
    /** The gradient end behind the floating landscape controls. */
    val controlsScrim: Color,
    /** The minimized bar. */
    val barBackground: Color,
    /** An active (switched-off) control button and its content. */
    val controlActiveBackground: Color,
    val controlActiveContent: Color,
    /** Text and icons drawn over video and on the bar. */
    val onOverlay: Color,
    /** The per-tile debug readout. */
    val statsBackground: Color,
    val pageDotInactive: Color,
) {
    companion object {
        /** Compound's dark semantic tokens, as of compound-design-tokens 2026. */
        val Dark = ElementCallColors(
            textPrimary = Color(0xFFEBEEF2),
            textSecondary = Color(0xFF808994),
            textCritical = Color(0xFFFD3E3C),
            iconPrimary = Color(0xFFEBEEF2),
            iconSecondary = Color(0xFF808994),
            iconCritical = Color(0xFFFD3E3C),
            iconAccent = Color(0xFF129A78),
            bgCanvas = Color(0xFF101317),
            bgSubtlePrimary = Color(0xFF26282D),
            bgSubtleSecondary = Color(0xFF1D1F24),
            borderActiveSpeaker = Color(0xFF003D29),
            borderThumbnail = Color(0xFF3C3F44),
            hangUp = Color(0xFFE5484D),
            sharingAccent = Color(0xFF25B39A),
            sharingBanner = Color(0xFF0F7B6C),
            overlayScrim = Color(0xCC15191E),
            controlsScrim = Color(0xB315191E),
            barBackground = Color(0xFF15191E),
            controlActiveBackground = Color.White,
            controlActiveContent = Color.Black,
            onOverlay = Color.White,
            statsBackground = Color(0xCC000000),
            pageDotInactive = Color(0x66FFFFFF),
        )
    }
}
