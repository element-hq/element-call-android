/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import org.matrix.rtc.CommandSenderCallback
import org.matrix.rtc.FfiJoinSessionParams
import org.matrix.rtc.FfiLeaveSessionParams
import org.matrix.rtc.FfiRaisedHand
import org.matrix.rtc.FfiReceivedEncryptionKey
import org.matrix.rtc.FfiRelationLookup
import org.matrix.rtc.FfiSlotEncryption
import org.matrix.rtc.FfiTimelineEvent
import org.matrix.rtc.LegacyStateMemberEvent
import org.matrix.rtc.MembershipSnapshotSubscription
import org.matrix.rtc.RawMemberEvent
import org.matrix.rtc.RtcSessionManagerHandleInterface
import org.matrix.rtc.SlotEvent
import org.matrix.rtc.StickyEvent
import java.time.Duration

/**
 * Records what the feeders hand over. Only the entry points [RoomStateFeeder] and [SessionStateFeeder]
 * use do anything; the rest of the handle exists to satisfy the interface.
 */
internal class RecordingSessionManager(
    /** What [memberCount] answers. Null is the core saying it holds no session for the slot. */
    private val coreMemberCount: ULong? = null,
) : RtcSessionManagerHandleInterface {
    val stickyStates = mutableListOf<Pair<String, List<StickyEvent>>>()
    val memberships = mutableListOf<Triple<String, List<RawMemberEvent>, List<LegacyStateMemberEvent>>>()
    val receivedKeys = mutableListOf<FfiReceivedEncryptionKey>()
    val receivedLegacyKeys = mutableListOf<LegacyKey>()

    data class LegacyKey(
        val sender: String,
        val contentJson: String,
        val wasEncrypted: Boolean,
        val senderDeviceId: String?,
        val senderIsCrossSigned: Boolean,
    )

    override suspend fun setCurrentStickyState(roomId: String, events: List<StickyEvent>) {
        stickyStates += roomId to events
    }

    override suspend fun setCurrentMembership(
        roomId: String,
        memberEvents: List<RawMemberEvent>,
        legacyStateEvents: List<LegacyStateMemberEvent>,
    ) {
        memberships += Triple(roomId, memberEvents, legacyStateEvents)
    }

    override suspend fun onRoomMembersReceived(roomId: String, joinedUserIds: List<String>) = Unit

    override suspend fun onRoomEncryptionReceived(roomId: String, encrypted: Boolean) = Unit

    override suspend fun onRoomSlotsReceived(roomId: String, slots: List<SlotEvent>) = Unit

    override suspend fun debugSnapshot(): String = ""

    override suspend fun heartbeat(roomId: String, slotId: String): Boolean = true

    override suspend fun join(params: FfiJoinSessionParams): String = ""

    override suspend fun leave(roomId: String, slotId: String, params: FfiLeaveSessionParams) = Unit

    override suspend fun memberCount(roomId: String, slotId: String): ULong? = coreMemberCount

    override suspend fun ownMemberId(roomId: String, slotId: String): String? = null

    override suspend fun openSlot(roomId: String, slotId: String, applicationType: String, encryption: FfiSlotEncryption?) = Unit

    override suspend fun closeSlot(roomId: String, slotId: String) = Unit

    override suspend fun receiveEncryptionKey(key: FfiReceivedEncryptionKey) {
        receivedKeys += key
    }

    override suspend fun receiveLegacyEncryptionKey(
        sender: String,
        contentJson: String,
        wasEncrypted: Boolean,
        senderDeviceId: String?,
        senderIsCrossSigned: Boolean,
    ) {
        receivedLegacyKeys += LegacyKey(sender, contentJson, wasEncrypted, senderDeviceId, senderIsCrossSigned)
    }

    override suspend fun sessionCount(): ULong = 0uL

    override suspend fun setCommandSender(callback: CommandSenderCallback) = Unit

    override fun startHeartbeat(roomId: String, slotId: String) = Unit

    override fun startHeartbeatEvery(roomId: String, slotId: String, interval: Duration) = Unit

    override fun stopHeartbeat(roomId: String, slotId: String) = Unit

    override suspend fun subscribeMembershipSnapshots(roomId: String, slotId: String): MembershipSnapshotSubscription? = null

    // Raised hands and reactions (plan 002): nothing under test reaches them yet.
    override suspend fun raiseHand(roomId: String, slotId: String) = Unit

    override suspend fun lowerHand(roomId: String, slotId: String) = Unit

    override suspend fun raisedHands(roomId: String, slotId: String): List<FfiRaisedHand> = emptyList()

    override suspend fun sendReaction(roomId: String, slotId: String, emoji: String, name: String): String = ""

    override suspend fun ownMembershipEventId(roomId: String, slotId: String): String? = null

    override suspend fun pendingRelationLookups(roomId: String): List<FfiRelationLookup> = emptyList()

    override suspend fun onRelationsReceived(roomId: String, targetEventId: String, events: List<FfiTimelineEvent>) = Unit

    override suspend fun onRoomTimelineEvents(roomId: String, events: List<FfiTimelineEvent>) = Unit

    override suspend fun onEventRedacted(roomId: String, eventId: String) = Unit
}
