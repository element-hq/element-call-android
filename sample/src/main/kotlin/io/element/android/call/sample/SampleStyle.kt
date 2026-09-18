/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.element.android.call.ui.theme.ElementCallAvatarData
import io.element.android.call.ui.theme.ElementCallColors
import io.element.android.call.ui.theme.ElementCallIcons
import io.element.android.call.ui.theme.ElementCallStyle
import io.element.android.call.ui.theme.ElementCallTypography

/**
 * A style no product would ship, on purpose: every colour the call reads is moved far enough from
 * the default that a composable still reading a literal shows up at a glance. Avatars are squares with
 * the whole localpart, which is how a host with its own avatar composable proves it is being called.
 */
@Composable
fun rememberLoudElementCallStyle(): ElementCallStyle {
    val icons = ElementCallIcons.default()
    return remember(icons) {
        ElementCallStyle(
            colors = ElementCallColors.Dark.copy(
                textPrimary = Color(0xFFFFF3B0),
                textSecondary = Color(0xFFE0C97F),
                iconPrimary = Color(0xFFFFF3B0),
                iconSecondary = Color(0xFFE0C97F),
                bgCanvas = Color(0xFF1B0A2E),
                bgSubtlePrimary = Color(0xFF3A1D5C),
                bgSubtleSecondary = Color(0xFF2A1445),
                borderActiveSpeaker = Color(0xFFFFD60A),
                borderThumbnail = Color(0xFFFFD60A),
                hangUp = Color(0xFFFF7A00),
                barBackground = Color(0xFF4B2A7A),
                controlActiveBackground = Color(0xFFFFD60A),
                controlActiveContent = Color(0xFF1B0A2E),
                overlayScrim = Color(0x99000000),
                controlsScrim = Color(0xCC1B0A2E),
                onOverlay = Color(0xFFFFF3B0),
                statsBackground = Color(0xCC3A1D5C),
                pageDotInactive = Color(0x66FFF3B0),
            ),
            typography = ElementCallTypography.Default,
            icons = icons,
            avatar = { data, size -> SquareAvatar(data = data, size = size) },
        )
    }
}

@Composable
private fun SquareAvatar(data: ElementCallAvatarData, size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 6))
            .background(Color(0xFFFFD60A)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = data.name ?: data.id.removePrefix("@").substringBefore(':'),
            color = Color(0xFF1B0A2E),
            fontSize = (size.value / 4).sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}
