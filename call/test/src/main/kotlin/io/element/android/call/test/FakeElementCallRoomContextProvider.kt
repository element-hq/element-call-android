/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.test

import io.element.android.call.api.ElementCallRoomContext
import io.element.android.call.api.ElementCallRoomContextProvider
import io.element.android.call.api.rtc.id.RoomId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * One room context, writable, for whatever room is asked about.
 */
class FakeElementCallRoomContextProvider(
    roomContext: ElementCallRoomContext? = ElementCallRoomContext(displayName = "A room", isDm = false, members = emptyMap()),
) : ElementCallRoomContextProvider {
    /** Null emits nothing, which is what a provider that knows nothing does. */
    val roomContext = MutableStateFlow(roomContext)

    override fun roomContext(roomId: RoomId): Flow<ElementCallRoomContext> = roomContext.filterNotNull()
}
