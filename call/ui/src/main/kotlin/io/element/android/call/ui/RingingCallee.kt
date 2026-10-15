/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.element.android.call.ui.theme.ElementCallAvatar
import io.element.android.call.ui.theme.ElementCallAvatarSize
import io.element.android.call.ui.theme.ElementCallTheme

/**
 * The callee a DM call is ringing, drawn as their tile will be drawn without video, so pickup only fades
 * their picture in (030 R15, R21). Read as "Calling" and their name (R22).
 */
@Composable
internal fun RingingCallee(tile: CallTileData, modifier: Modifier = Modifier) {
    val isNotSent = tile.ring == CallRing.NOT_SENT
    val description = if (isNotSent) {
        stringResource(R.string.element_call_ring_not_sent)
    } else {
        stringResource(R.string.element_call_a11y_calling, tile.displayName)
    }
    Box(
        modifier = modifier
            .background(ElementCallTheme.colors.bgSubtlePrimary)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ElementCallAvatar(userId = tile.userId, roomMember = tile.roomMember, size = ElementCallAvatarSize.Spotlight)
            if (isNotSent) {
                Text(
                    text = stringResource(R.string.element_call_ring_not_sent),
                    style = ElementCallTheme.typography.bodyMdMedium,
                    color = ElementCallTheme.colors.textCritical,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp, start = 24.dp, end = 24.dp),
                )
            }
        }
    }
}
