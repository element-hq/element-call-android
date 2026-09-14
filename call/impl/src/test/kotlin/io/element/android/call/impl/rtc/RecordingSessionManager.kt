/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrixrtc.impl

import uniffi.matrix_rtc_ffi.CommandSenderCallback
import uniffi.matrix_rtc_ffi.FfiJoinSessionParams
import uniffi.matrix_rtc_ffi.FfiLeaveSessionParams
import uniffi.matrix_rtc_ffi.FfiReceivedEncryptionKey
import uniffi.matrix_rtc_ffi.FfiSlotEncryption
import uniffi.matrix_rtc_ffi.LegacyStateMemberEvent
import uniffi.matrix_rtc_ffi.MembershipSnapshotSubscription
import uniffi.matrix_rtc_ffi.RawMemberEvent
import uniffi.matrix_rtc_ffi.RtcSessionManagerHandleInterface
import uniffi.matrix_rtc_ffi.SlotEvent
import uniffi.matrix_rtc_ffi.StickyEvent
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

    override suspend fun onRoomMembersReceived(roomId: String, userIds: List<String>) = Unit

    override suspend fun onRoomEncryptionReceived(roomId: String, isEncrypted: Boolean) = Unit

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

    override suspend fun setCommandSender(sender: CommandSenderCallback) = Unit

    override fun startHeartbeat(roomId: String, slotId: String) = Unit

    override fun startHeartbeatEvery(roomId: String, slotId: String, period: Duration) = Unit

    override fun stopHeartbeat(roomId: String, slotId: String) = Unit

    override suspend fun subscribeMembershipSnapshots(roomId: String, slotId: String): MembershipSnapshotSubscription? = null
}
