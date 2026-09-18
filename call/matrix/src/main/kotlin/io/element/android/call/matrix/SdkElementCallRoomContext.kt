/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.matrix

import io.element.android.call.api.ElementCallDispatchers
import io.element.android.call.api.ElementCallRoomContext
import io.element.android.call.api.ElementCallRoomContextProvider
import io.element.android.call.api.ElementCallRoomMember
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.api.rtc.id.UserId
import io.element.android.call.matrix.util.runCatchingExceptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.Room
import timber.log.Timber

/**
 * The uncached default [ElementCallRoomContextProvider] over a Rust SDK [Client], for a host that has nothing
 * better: the room's name, whether it is a DM, and its members' names and avatars, re-read from the SDK each
 * time the room info changes.
 *
 * Element X does not use it. Its room cache reads members from disk before the network and applies its own
 * display-name fallbacks, which are product decisions; that is why the room context is a port (plan §4.3)
 * and this is only the default behind it.
 */
class SdkElementCallRoomContext(
    private val client: Client,
    private val dispatchers: ElementCallDispatchers = ElementCallDispatchers.Default,
) : ElementCallRoomContextProvider {
    /** Emits nothing for a room the client is not joined to: a provider that knows nothing says nothing. */
    override fun roomContext(roomId: RoomId): Flow<ElementCallRoomContext> = flow {
        val room = withContext(dispatchers.io) {
            runCatchingExceptions { client.getRoom(roomId.value) }.getOrNull()
        }
        if (room == null) {
            Timber.w("ElementCallMatrix: $roomId is not a joined room, no room context for the call")
            return@flow
        }
        // The first read may fetch the members from the homeserver, which a room we are joined to but have
        // never opened needs. Later reads stay in the store: a room info update is not a reason to hit the
        // network again.
        var hasLoadedMembers = false
        emitAll(
            room.roomInfoUpdates(dispatchers).map { info ->
                val members = room.loadMembers(roomId, fromStoreOnly = hasLoadedMembers)
                hasLoadedMembers = true
                ElementCallRoomContext(
                    displayName = info.displayName,
                    isDm = info.isDm,
                    members = members,
                )
            }
        )
    }

    private suspend fun Room.loadMembers(roomId: RoomId, fromStoreOnly: Boolean): Map<UserId, ElementCallRoomMember> =
        withContext(dispatchers.io) {
            runCatchingExceptions {
                val iterator = if (fromStoreOnly) membersNoSync() else members()
                iterator.use { it.nextChunk(it.len()).orEmpty() }
                    .mapNotNull { member ->
                        val userId = runCatchingExceptions { UserId(member.userId) }.getOrNull() ?: return@mapNotNull null
                        userId to ElementCallRoomMember(
                            userId = userId,
                            displayName = member.displayName,
                            avatarUrl = member.avatarUrl,
                        )
                    }
                    .toMap()
            }.onFailure {
                Timber.w(it, "ElementCallMatrix: cannot load the members of $roomId")
            }.getOrDefault(emptyMap())
        }
}
