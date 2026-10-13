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

/** The design's rings: a vertical gradient between two colours over a diagonal one, as CSS layers them. */
enum class ElementCallBorderGradient(internal val from: Color, internal val to: Color) {
    /** Blue to teal: whoever is speaking. */
    ACTIVE_SPEAKER(Color(0xFF0D5CBD), Color(0xFF0DBDA8)),

    /** Orange to yellow: a raised hand. */
    RAISED_HAND(Color(0xFFDC6700), Color(0xFFF1BD00)),
    ;

    /** The ring as a brush, sized to whatever it strokes. */
    val brush: ShaderBrush = BorderGradientBrush(this)
}

private class BorderGradientBrush(private val gradient: ElementCallBorderGradient) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val vertical = LinearGradientShader(
            from = Offset.Zero,
            to = Offset(0f, size.height),
            colors = listOf(gradient.from.copy(alpha = 0.9f), gradient.to.copy(alpha = 0.9f)),
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
            colors = listOf(gradient.from.copy(alpha = 0.7f), gradient.to.copy(alpha = 0.7f)),
        )
        return ComposeShader(diagonal, vertical, PorterDuff.Mode.SRC_OVER)
    }

    override fun equals(other: Any?) = other is BorderGradientBrush && other.gradient == gradient

    override fun hashCode() = gradient.hashCode()

    private companion object {
        const val DIAGONAL_CSS_DEGREES = 119.36
    }
}
