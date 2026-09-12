/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

// The matrix-rust-rtc core, as a locally built AAR: `./tools/rtc/build-rust-rtc` drops it here
// (gitignored), and this bare project publishes it on its `default` configuration so that call/impl
// and call/ui resolve the same file. See docs/local_stack.md, layer 1.
//
// Temporary: the core has no Maven coordinate yet. When it publishes one, this project goes and the
// consumers switch to a catalog entry.
val aar = file("matrixrtc-release.aar")
if (!aar.exists()) {
    logger.warn(
        "\nNote: rtc/local/matrixrtc-release.aar is missing; call/impl and call/ui will not build. " +
            "Run ./tools/rtc/build-rust-rtc or copy an AAR in place (docs/local_stack.md, layer 1).\n"
    )
}
configurations.maybeCreate("default")
artifacts.add("default", aar)
