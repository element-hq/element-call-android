/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:Suppress("DEPRECATION")

package base

import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider
import sergio.sastre.composable.preview.scanner.android.AndroidComposablePreviewScanner
import sergio.sastre.composable.preview.scanner.android.AndroidPreviewInfo
import sergio.sastre.composable.preview.scanner.core.preview.ComposablePreview

/**
 * The library's package tree only. Element X's own scanner stops at `io.element.android.{features,
 * libraries, ...}`, so when both are on one classpath neither re-snapshots the other's previews.
 */
private val PACKAGE_TREES = arrayOf(
    "io.element.android.call",
)

object ComposablePreviewProvider : TestParameterValuesProvider() {
    val values: List<IndexedValue<ComposablePreview<AndroidPreviewInfo>>> by lazy {
        AndroidComposablePreviewScanner()
            .scanPackageTrees(*PACKAGE_TREES)
            .getPreviews()
            .withIndex()
            .toList()
    }

    override fun provideValues(context: Context): List<IndexedValue<ComposablePreview<AndroidPreviewInfo>>> = values
}

object Shard1ComposablePreviewProvider : TestParameterValuesProvider() {
    override fun provideValues(context: Context): List<ComposablePreview<AndroidPreviewInfo>> =
        ComposablePreviewProvider.values.filter { it.index % 2 == 0 }.map { it.value }
}

object Shard2ComposablePreviewProvider : TestParameterValuesProvider() {
    override fun provideValues(context: Context): List<ComposablePreview<AndroidPreviewInfo>> =
        ComposablePreviewProvider.values.filter { it.index % 2 == 1 }.map { it.value }
}
