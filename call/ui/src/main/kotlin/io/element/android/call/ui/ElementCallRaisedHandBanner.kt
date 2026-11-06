/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.element.android.call.ui.preview.ElementCallPreview
import io.element.android.call.ui.preview.PreviewsDayNight
import io.element.android.call.ui.theme.ElementCallTheme

/**
 * Above the control bar while our hand is up: that it is, and the way down in one tap, since the control that raised it
 * may be behind More (feature-hq 009 R2, R17). It floats over the stage, so a raise moves no tile.
 */
@Composable
internal fun ElementCallRaisedHandBanner(isEnabled: Boolean, onLower: () -> Unit, modifier: Modifier = Modifier) {
    val lowerHand = stringResource(R.string.element_call_a11y_lower_hand)
    Row(
        modifier = modifier
            .clip(BANNER_SHAPE)
            .background(ElementCallTheme.colors.bgSubtleSecondary)
            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = ElementCallTheme.icons.handRaised,
            contentDescription = null,
            tint = ElementCallTheme.colors.iconPrimary,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = stringResource(R.string.element_call_hand_raised_banner),
            style = ElementCallTheme.typography.bodyMdRegular,
            color = ElementCallTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 8.dp),
        )
        // Design's 32dp pill: Material would grow the banner round a 48dp touch area.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            Button(
            onClick = onLower,
            enabled = isEnabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = ElementCallTheme.colors.bgActionPrimaryRest,
                contentColor = ElementCallTheme.colors.iconOnSolidPrimary,
                disabledContainerColor = ElementCallTheme.colors.bgActionPrimaryRest.copy(alpha = DISABLED_ALPHA),
                disabledContentColor = ElementCallTheme.colors.iconOnSolidPrimary.copy(alpha = DISABLED_ALPHA),
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            modifier = Modifier
                .height(32.dp)
                .semantics { contentDescription = lowerHand }
                .testTag(ElementCallTestTags.LOWER_HAND),
        ) {
            Text(text = stringResource(R.string.element_call_action_cancel), style = ElementCallTheme.typography.bodyMdMedium)
        }
        }
    }
}

private const val DISABLED_ALPHA = 0.4f

private val BANNER_SHAPE = RoundedCornerShape(percent = 50)

@PreviewsDayNight
@Composable
internal fun ElementCallRaisedHandBannerPreview() = ElementCallPreview {
    ElementCallRaisedHandBanner(isEnabled = true, onLower = {}, modifier = Modifier.padding(8.dp))
}
