/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The text styles the call composables read, named after the Compound scale they default to.
 */
@Immutable
data class ElementCallTypography(
    val headingMdBold: TextStyle,
    val bodyLgMedium: TextStyle,
    val bodyMdMedium: TextStyle,
    val bodyMdRegular: TextStyle,
    val bodySmMedium: TextStyle,
    val bodySmRegular: TextStyle,
    val bodyXsMedium: TextStyle,
    val bodyXsRegular: TextStyle,
) {
    companion object {
        /** Compound's typography tokens, with the platform's default font. */
        val Default = ElementCallTypography(
            headingMdBold = TextStyle(fontWeight = FontWeight.W700, fontSize = 22.sp, lineHeight = 27.sp, letterSpacing = 0.em),
            bodyLgMedium = TextStyle(fontWeight = FontWeight.W500, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.015625.em),
            bodyMdMedium = TextStyle(fontWeight = FontWeight.W500, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.017857.em),
            bodyMdRegular = TextStyle(fontWeight = FontWeight.W400, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.017857.em),
            bodySmMedium = TextStyle(fontWeight = FontWeight.W500, fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.033333.em),
            bodySmRegular = TextStyle(fontWeight = FontWeight.W400, fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.033333.em),
            bodyXsMedium = TextStyle(fontWeight = FontWeight.W500, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.045454.em),
            bodyXsRegular = TextStyle(fontWeight = FontWeight.W400, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.045454.em),
        )
    }
}
