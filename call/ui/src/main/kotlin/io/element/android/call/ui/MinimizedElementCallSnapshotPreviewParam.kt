/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.call.api.ElementCallSnapshot

/**
 * The picture-in-picture window, which shows one member: the states are about who that is and whether
 * they have video.
 */
open class MinimizedElementCallSnapshotPreviewParam : PreviewParameterProvider<ElementCallSnapshot> {
    override val values: Sequence<ElementCallSnapshot>
        get() = sequenceOf(
            // The other person, camera on: the case the window exists for.
            aCallSnapshot(participants = listOf(aLocalParticipant(), aRemoteCameraParticipant())),
            // The other person, camera off: their avatar instead of a black rectangle.
            aCallSnapshot(participants = listOf(aLocalParticipant(), aRemoteParticipant())),
            // Alone in the call: ourselves, since there is nobody else to show.
            aCallSnapshot(participants = listOf(aLocalParticipant())),
        )
}
