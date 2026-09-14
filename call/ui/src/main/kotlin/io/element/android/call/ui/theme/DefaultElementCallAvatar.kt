/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

/**
 * The default avatar: a coloured disc with the first letter of the name, or of the user id's localpart.
 * A host that can load images provides its own through [ElementCallStyle.avatar].
 */
@Composable
fun DefaultElementCallAvatar(
    data: ElementCallAvatarData,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val initial = (data.name ?: data.id.removePrefix("@"))
        .firstOrNull { it.isLetterOrDigit() }
        ?.uppercaseChar()
        ?.toString()
        ?: "?"
    val background = AVATAR_COLORS[(data.id.hashCode() and Int.MAX_VALUE) % AVATAR_COLORS.size]
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            color = Color.White,
            fontSize = (size.value / 2).sp,
            fontWeight = FontWeight.W600,
            textAlign = TextAlign.Center,
        )
    }
}

/** Compound's avatar background colours, dark theme. */
private val AVATAR_COLORS = listOf(
    Color(0xFF0B6F7A),
    Color(0xFF7A3F0B),
    Color(0xFF6B2FA3),
    Color(0xFF0B5C3F),
    Color(0xFF8A1C3F),
    Color(0xFF2F4FA3),
)
