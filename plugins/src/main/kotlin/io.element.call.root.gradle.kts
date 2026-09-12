/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

/**
 * This will generate the plugin "io.element.call.root", applied by the root project only.
 */
import extension.setupKover

plugins {
    id("org.jetbrains.kotlinx.kover") apply false
}

setupKover()
