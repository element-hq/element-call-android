/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package base

import app.cash.paparazzi.DeviceConfig

enum class BaseDeviceConfig(
    val deviceConfig: DeviceConfig,
) {
    NEXUS_5(DeviceConfig.NEXUS_5),
}
