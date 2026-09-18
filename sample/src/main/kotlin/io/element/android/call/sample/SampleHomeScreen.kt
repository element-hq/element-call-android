/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList

/**
 * The picker: one row per fixture, and the style switch. Deliberately plain, so that whatever the call
 * draws over it is unmistakably the call's.
 */
@Composable
fun SampleHomeScreen(
    fixtures: ImmutableList<SampleFixture>,
    isStyleOverridden: Boolean,
    onToggleStyle: () -> Unit,
    onOpen: (SampleFixture) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.safeDrawingPadding()) {
            item {
                Text(
                    text = "Element Call sample",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                Text(
                    text = "Every row opens a call over this screen, with colour bars where a camera would be. " +
                        "Launch straight into one with `adb shell am start -n io.element.android.call.sample/.SampleActivity --es fixture <key>`.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onToggleStyle)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Host style override", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "Draws the call with a deliberately loud ElementCallStyle instead of the defaults.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = isStyleOverridden, onCheckedChange = { onToggleStyle() })
                }
                HorizontalDivider()
            }
            items(fixtures, key = { it.key }) { fixture ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(fixture) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(text = fixture.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = fixture.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = fixture.key,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}
