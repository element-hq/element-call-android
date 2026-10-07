/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui.theme

import android.graphics.ComposeShader
import android.graphics.PorterDuff
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** The design's speaking ring: a vertical blue to teal gradient over a diagonal one, as CSS layers them. */
internal object ActiveSpeakerBrush : ShaderBrush() {
    private val blue = Color(0xFF0D5CBD)
    private val teal = Color(0xFF0DBDA8)
    private const val DIAGONAL_CSS_DEGREES = 119.36

    override fun createShader(size: Size): Shader {
        val vertical = LinearGradientShader(
            from = Offset.Zero,
            to = Offset(0f, size.height),
            colors = listOf(blue.copy(alpha = 0.9f), teal.copy(alpha = 0.9f)),
        )
        // CSS gradient line: through the centre, long enough that the corners get the end colours.
        val angle = Math.toRadians(DIAGONAL_CSS_DEGREES)
        val dx = sin(angle).toFloat()
        val dy = -cos(angle).toFloat()
        val half = (abs(size.width * dx) + abs(size.height * dy)) / 2
        val centre = size.center
        val diagonal = LinearGradientShader(
            from = Offset(centre.x - dx * half, centre.y - dy * half),
            to = Offset(centre.x + dx * half, centre.y + dy * half),
            colors = listOf(blue.copy(alpha = 0.7f), teal.copy(alpha = 0.7f)),
        )
        return ComposeShader(diagonal, vertical, PorterDuff.Mode.SRC_OVER)
    }
}
