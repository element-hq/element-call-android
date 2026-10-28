/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.element.android.call.api.rtc.MatrixRtcMembershipFormat
import io.element.android.call.sample.lab.label

/**
 * The lab tab (feature-hq `harness/lab.md`): a real call over a logged-in account. The account kept on this phone with
 * its call and its logout, or how to log in, which is scanning the lab page's code.
 */
@Composable
fun SampleLabScreen(
    account: String?,
    format: MatrixRtcMembershipFormat?,
    notice: String?,
    onScan: () -> Unit,
    onRejoin: () -> Unit,
    onLogOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = notice ?: account?.let { "Signed in as $it. A code for another account logs this one out first." }
        ?: "Scan an account's code on the lab page to log in and join its call."
    val rows = if (account == null) listOf(LabRow.SCAN) else listOf(LabRow.FORMAT, LabRow.REJOIN, LabRow.SCAN, LabRow.LOG_OUT)
    Surface(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.safeDrawingPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item {
                Text(
                    text = "Lab",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            section(key = "lab", title = "Account", description = description, rows = rows) { row, shapes ->
                when (row) {
                    LabRow.FORMAT -> SegmentedListItem(
                        onClick = onScan,
                        shapes = shapes,
                        colors = sectionColors(),
                        supportingContent = { Text("Set by the lab page; scan its code again after switching it there.") },
                        content = { Text("Call format: ${format?.label ?: "unknown"}", fontWeight = FontWeight.Bold) },
                    )
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
    }
}

private enum class LabRow { FORMAT, SCAN, REJOIN, LOG_OUT }
