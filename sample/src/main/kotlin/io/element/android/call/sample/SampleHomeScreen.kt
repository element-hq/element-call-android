/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList

/**
 * The picker: the host switches, then the fixtures by category and the scenarios, the sections the iOS
 * catalogue has, each a Material segmented list. Deliberately plain, so that whatever the call
 * draws over it is unmistakably the call's.
 */
@Composable
fun SampleHomeScreen(
    fixtures: ImmutableList<SampleFixture>,
    scenarios: ImmutableList<String>,
    isStyleOverridden: Boolean,
    onToggleStyle: () -> Unit,
    onAnswerOnLockScreen: () -> Unit,
    onOpen: (SampleFixture) -> Unit,
    onOpenRinging: (SampleRingingFixture) -> Unit,
    onOpenOutage: (SampleOutageFixture) -> Unit,
    onOpenScenario: (String) -> Unit,
    labAccount: String?,
    labNotice: String?,
    onScanLab: () -> Unit,
    onRejoinLab: () -> Unit,
    onLogOutLab: () -> Unit,
    modifier: Modifier = Modifier,
    launchError: String? = null,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.safeDrawingPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
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
            }
            if (launchError != null) {
                item {
                    Text(
                        text = launchError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
            labSection(labAccount, labNotice, onScanLab, onRejoinLab, onLogOutLab)
            section(key = "host", title = "Host", rows = listOf(0, 1)) { row, shapes ->
                if (row == 0) {
                    SegmentedListItem(
                        checked = isStyleOverridden,
                        onCheckedChange = { onToggleStyle() },
                        shapes = shapes,
                        colors = sectionColors(),
                        supportingContent = { Text("Draws the call with a deliberately loud ElementCallStyle instead of the defaults.") },
                        trailingContent = { Switch(checked = isStyleOverridden, onCheckedChange = null) },
                        content = { Text("Host style override") },
                    )
                } else {
                    SegmentedListItem(
                        onClick = onAnswerOnLockScreen,
                        shapes = shapes,
                        colors = sectionColors(),
                        supportingContent = { Text("Lock the phone within 5 s: a group call then opens over the keyguard, without this screen.") },
                        content = { Text("Answer on the lock screen") },
                    )
                }
            }
            SampleFixture.Category.entries.forEach { category ->
                section(key = category.name, title = category.title, rows = fixtures.filter { it.category == category }) { fixture, shapes ->
                    FixtureRow(title = fixture.title, description = fixture.description, launchKey = fixture.key, shapes = shapes) { onOpen(fixture) }
                }
            }
            section(
                key = "ringing",
                title = "Ringing",
                description = "A DM call to Bob on the real controller, with its sounds: the ring goes out at 0.5 s, the outcome comes at 6 s.",
                rows = SampleRingingFixture.entries,
            ) { fixture, shapes ->
                FixtureRow(title = fixture.title, description = fixture.description, launchKey = fixture.key, shapes = shapes) { onOpenRinging(fixture) }
            }
            section(
                key = "outages",
                title = "Outages",
                description = "A group call on the real controller whose interruptions record changes on a timeline, as the core's would (034).",
                rows = SampleOutageFixture.entries,
            ) { fixture, shapes ->
                FixtureRow(title = fixture.title, description = fixture.description, launchKey = fixture.key, shapes = shapes) { onOpenOutage(fixture) }
            }
            section(
                key = "scenarios",
                title = "Scenarios",
                description = "The layout timelines from feature-hq, played under a scrubber. Roster frames apply themselves; " +
                    "scroll, rotate and swipe are cues for you. `--es scenario <name>` launches one.",
                rows = scenarios,
            ) { name, shapes ->
                FixtureRow(title = name, shapes = shapes) { onOpenScenario(name) }
            }
        }
    }
}

/**
 * The lab (feature-hq `harness/lab.md`): the account kept on this phone with its call and its logout, or how to log in,
 * which is scanning the lab page's code with the camera.
 */
private fun LazyListScope.labSection(account: String?, notice: String?, onScan: () -> Unit, onRejoin: () -> Unit, onLogOut: () -> Unit) {
    val description = notice ?: account?.let { "Signed in as $it. A code for another account logs this one out first." }
        ?: "Scan an account's code on the lab page to log in and join its call."
    val rows = if (account == null) listOf(LabRow.SCAN) else listOf(LabRow.REJOIN, LabRow.SCAN, LabRow.LOG_OUT)
    section(key = "lab", title = "Lab", description = description, rows = rows) { row, shapes ->
        when (row) {
            LabRow.SCAN -> SegmentedListItem(
                onClick = onScan,
                shapes = shapes,
                colors = sectionColors(),
                supportingContent = { Text("The camera app cannot open a lab code; scan it here.") },
                content = { Text("Scan a lab code") },
            )
            LabRow.REJOIN -> SegmentedListItem(
                onClick = onRejoin,
                shapes = shapes,
                colors = sectionColors(),
                supportingContent = { Text("The same device, back into the lab call.") },
                content = { Text("Rejoin the lab call") },
            )
            LabRow.LOG_OUT -> SegmentedListItem(
                onClick = onLogOut,
                shapes = shapes,
                colors = sectionColors(),
                supportingContent = { Text("Logs this device out on the lab's homeserver and forgets it.") },
                content = { Text("Log out", color = MaterialTheme.colorScheme.error) },
            )
        }
    }
}

private enum class LabRow { SCAN, REJOIN, LOG_OUT }

/** A titled group of rows, rounded as one card and split by thin gaps, the Material counterpart of an iOS inset grouped list. */
private fun <T> LazyListScope.section(
    key: String,
    title: String,
    rows: List<T>,
    description: String? = null,
    row: @Composable (T, ListItemShapes) -> Unit,
) {
    item(key = "section:$key") {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
    itemsIndexed(rows, key = { index, _ -> "$key:$index" }) { index, entry ->
        Column(modifier = Modifier.padding(top = if (index == 0) 0.dp else ListItemDefaults.SegmentedGap)) {
            row(entry, ListItemDefaults.segmentedShapes(index = index, count = rows.size))
        }
    }
}

@Composable
private fun FixtureRow(
    title: String,
    shapes: ListItemShapes,
    description: String? = null,
    launchKey: String? = null,
    onClick: () -> Unit,
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = sectionColors(),
        supportingContent = if (description == null && launchKey == null) {
            null
        } else {
            {
                Column {
                    description?.let { Text(it) }
                    launchKey?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        },
        content = { Text(title) },
    )
}

@Composable
private fun sectionColors(): ListItemColors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
