/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api

import io.element.android.call.api.rtc.id.RoomId

/**
 * What a host asks for when it starts a call: which room, and whether the user asked for video.
 *
 * No session id: a stack is built per Matrix session, so the session is the one the stack belongs to.
 */
data class ElementCallData(
    val roomId: RoomId,
    /** True for a voice call, false when the user asked for video: decides the camera and the loudspeaker at start. */
    val isAudioCall: Boolean,
)
