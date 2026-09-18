/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.matrix.ElementCallRoomStateEvent
import io.element.android.call.api.matrix.ElementCallStickyEvent
import io.element.android.call.api.rtc.MatrixRtcElementCallCompat
import io.element.android.call.api.rtc.MatrixRtcEventTypes
import io.element.android.call.api.rtc.id.EventId
import io.element.android.call.api.rtc.id.UserId
import io.element.android.call.test.A_ROOM_ID
import io.element.android.call.test.A_USER_ID
import io.element.android.call.test.FakeElementCallMatrixRoom
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.matrix.rtc.RtcSessionManagerHandleInterface

@OptIn(ExperimentalCoroutinesApi::class)
class RoomStateFeederTest {
    @Test
    fun `a sticky membership reaches the core as the room's current state`() = runTest {
        val manager = RecordingSessionManager()
        val stickyEvents = MutableStateFlow(listOf(aMemberStickyEvent()))

        startFeeder(manager, stickyEvents)

        assertThat(manager.stickyStates).hasSize(1)
        val (roomId, events) = manager.stickyStates.single()
        assertThat(roomId).isEqualTo(A_ROOM_ID.value)
        assertThat(events.map { it.memberId }).containsExactly(A_MEMBER_ID)
    }

    /**
     * `setCurrentStickyState` replaces rather than merges, so an empty list is not "nothing to say" -
     * it is how the last member of a call departs. Filtering it out as a not-loaded-yet state, which is
     * the right thing to do for the room member feed, would leave the core holding a membership that
     * has already expired.
     */
    @Test
    fun `an emptied snapshot is fed rather than skipped`() = runTest {
        val manager = RecordingSessionManager()
        val stickyEvents = MutableStateFlow(listOf(aMemberStickyEvent()))
        startFeeder(manager, stickyEvents)

        stickyEvents.value = emptyList()
        runCurrent()

        assertThat(manager.stickyStates.map { (_, events) -> events.size }).containsExactly(1, 0).inOrder()
    }

    /**
     * A sticky event of a type the core does not handle, or one we cannot map, must not take the rest of
     * the snapshot with it - and must not be sent either, or it would count as a member.
     */
    @Test
    fun `an unmappable event is dropped without dropping the snapshot`() = runTest {
        val manager = RecordingSessionManager()
        val stickyEvents = MutableStateFlow(
            listOf(
                aMemberStickyEvent(),
                // No slot_id, so unmappable.
                aMemberStickyEvent(memberId = "unmappable", contentJson = """{"member":{"id":"unmappable"}}"""),
            )
        )

        startFeeder(manager, stickyEvents)

        assertThat(manager.stickyStates.single().second.map { it.memberId }).containsExactly(A_MEMBER_ID)
    }

    /**
     * The two entry points are mutually exclusive. Feeding both would not merely be redundant: each
     * replaces the room's whole membership, so the core's roster would flicker between the two views.
     */
    @Test
    fun `a compatibility session feeds raw membership instead of parsed sticky state`() = runTest {
        val manager = RecordingSessionManager()
        val stickyEvents = MutableStateFlow(listOf(aMemberStickyEvent()))

        startFeeder(manager, stickyEvents, elementCallCompat = MatrixRtcElementCallCompat.STICKY_EVENTS)

        assertThat(manager.stickyStates).isEmpty()
        val (roomId, memberEvents, legacyStateEvents) = manager.memberships.single()
        assertThat(roomId).isEqualTo(A_ROOM_ID.value)
        assertThat(memberEvents.single().eventType).isEqualTo(MatrixRtcEventTypes.MEMBER_UNSTABLE)
        // The whole content, not fields we picked out: an Element Call membership states its transports
        // somewhere else and its membership nowhere at all, so the library is the side that parses it.
        assertThat(memberEvents.single().contentJson).contains(A_MEMBER_ID)
        assertThat(legacyStateEvents).isEmpty()
    }

    /**
     * An Element Call membership carries no `slot_id` and no `membership`, which is exactly what [map]
     * refuses. It must still reach the core, or the peer we turned this mode on for is invisible.
     */
    @Test
    fun `a membership the spec mapper would drop still reaches the core in compatibility`() = runTest {
        val manager = RecordingSessionManager()
        val legacyContent = """{"member":{"user_id":"@bob:example.org","device_id":"BOB","id":"legacyMember"},"rtc_transports":[]}"""
        val stickyEvents = MutableStateFlow(
            listOf(aMemberStickyEvent(memberId = "legacyMember", contentJson = legacyContent))
        )

        startFeeder(manager, stickyEvents, elementCallCompat = MatrixRtcElementCallCompat.STICKY_EVENTS)

        assertThat(manager.memberships.single().second).hasSize(1)
    }

    /**
     * The membership of that generation is room state, so this is the only feed with anything to say
     * in this mode - and it must go in the legacy list, not the sticky one, or the library parses it
     * as MSC4143 and finds nothing it recognises.
     */
    @Test
    fun `a state-carried call feeds its room state as legacy membership`() = runTest {
        val manager = RecordingSessionManager()
        val stateEvents = MutableStateFlow(listOf(aMemberStateEvent()))

        startFeeder(manager, stateEvents = stateEvents, elementCallCompat = MatrixRtcElementCallCompat.STATE_EVENTS)

        assertThat(manager.stickyStates).isEmpty()
        val (roomId, memberEvents, legacyStateEvents) = manager.memberships.single()
        assertThat(roomId).isEqualTo(A_ROOM_ID.value)
        assertThat(memberEvents).isEmpty()
        val fed = legacyStateEvents.single()
        assertThat(fed.stateKey).isEqualTo(A_STATE_KEY)
        assertThat(fed.sender).isEqualTo(A_USER_ID.value)
        assertThat(fed.originServerTs).isEqualTo(1_700_000_000_000uL)
        // Handed over whole, as the sticky compatibility path does: the library owns this dialect.
        assertThat(fed.contentJson).contains("memberships")
    }

    /**
     * Room state is replaced, never removed, so an empty list cannot mean "everyone left" - it means
     * nobody ever published here, or the bucket has not synced. `setCurrentMembership` replaces rather
     * than merges, so feeding one says "this call is deserted", ourselves included: the roster drops to
     * zero, the core stops refreshing a session it thinks we left, and the dead man's switch fires
     * mid-call. Observed against Element Call.
     */
    @Test
    fun `an empty state snapshot is not fed, unlike an empty sticky one`() = runTest {
        val manager = RecordingSessionManager()
        val stateEvents = MutableStateFlow(emptyList<ElementCallRoomStateEvent>())

        startFeeder(manager, stateEvents = stateEvents, elementCallCompat = MatrixRtcElementCallCompat.STATE_EVENTS)

        assertThat(manager.memberships).isEmpty()

        // And once there is something to say, it is said - the filter is not a latch.
        stateEvents.value = listOf(aMemberStateEvent())
        runCurrent()

        assertThat(manager.memberships).hasSize(1)
    }

    /**
     * How that generation leaves a call: the state key stays, its content is emptied. Dropping it as
     * unusable would leave the departed member in the core's roster until their expiry lapsed, and
     * would leave them holding a media key they should have been rotated out of.
     */
    @Test
    fun `a departure with empty content is fed rather than dropped`() = runTest {
        val manager = RecordingSessionManager()
        val stateEvents = MutableStateFlow(listOf(aMemberStateEvent(contentJson = "{}")))

        startFeeder(manager, stateEvents = stateEvents, elementCallCompat = MatrixRtcElementCallCompat.STATE_EVENTS)

        assertThat(manager.memberships.single().third.single().contentJson).isEqualTo("{}")
    }

    /**
     * The SDK re-reads the whole state on every store wake, far more often than a membership changes,
     * and nothing promises the order is stable between reads. Unsorted, a reshuffle would look like a
     * change and turn each redundant read into a full membership replacement.
     */
    @Test
    fun `a reordered but unchanged state snapshot is fed only once`() = runTest {
        val manager = RecordingSessionManager()
        val first = aMemberStateEvent(stateKey = "_@alice_A")
        val second = aMemberStateEvent(stateKey = "_@bob_B")
        val stateEvents = MutableStateFlow(listOf(first, second))

        startFeeder(manager, stateEvents = stateEvents, elementCallCompat = MatrixRtcElementCallCompat.STATE_EVENTS)

        stateEvents.value = listOf(second, first)
        runCurrent()

        assertThat(manager.memberships).hasSize(1)
        assertThat(manager.memberships.single().third.map { it.stateKey }).containsExactly("_@alice_A", "_@bob_B").inOrder()
    }

    /**
     * We subscribe to one type and the SDK resolves the alias into the same bucket, so anything else
     * coming back means the bucket held something other than a membership. Feeding it would turn it
     * into one.
     */
    @Test
    fun `a state event of another type is dropped`() = runTest {
        val manager = RecordingSessionManager()
        val stateEvents = MutableStateFlow(
            listOf(aMemberStateEvent(), aMemberStateEvent(stateKey = "").copy(eventType = "m.room.topic"))
        )

        startFeeder(manager, stateEvents = stateEvents, elementCallCompat = MatrixRtcElementCallCompat.STATE_EVENTS)

        assertThat(manager.memberships.single().third.map { it.stateKey }).containsExactly(A_STATE_KEY)
    }

    /**
     * A timestamp is only ever absent for the stripped state of a room we are merely invited to, which
     * we cannot be in while joined to a call there. Dropping the event would throw away a membership
     * whose content may well carry its own `created_ts`; 0 lets the library decide, and reads as long
     * expired if it has nothing better.
     */
    @Test
    fun `a state membership with no timestamp is fed as long expired rather than dropped`() = runTest {
        val manager = RecordingSessionManager()
        val stateEvents = MutableStateFlow(listOf(aMemberStateEvent(timestampMs = null)))

        startFeeder(manager, stateEvents = stateEvents, elementCallCompat = MatrixRtcElementCallCompat.STATE_EVENTS)

        assertThat(manager.memberships.single().third.single().originServerTs).isEqualTo(0uL)
    }

    /**
     * The core needs the room's members to place a membership's sender; without them every entry in the
     * snapshot is excluded as `SenderNotInRoom`. The state feed is handed its snapshot the instant it
     * subscribes, so it is the one most likely to win that race - and in this mode nothing republishes
     * for minutes afterwards to cover for it.
     */
    @Test
    fun `no membership is fed before the core knows who is in the room`() = runTest {
        val manager = RecordingSessionManager()
        val stateEvents = MutableStateFlow(listOf(aMemberStateEvent()))

        startFeeder(
            manager,
            stateEvents = stateEvents,
            elementCallCompat = MatrixRtcElementCallCompat.STATE_EVENTS,
            joinedMemberIds = emptyFlow(),
        )

        assertThat(manager.memberships).isEmpty()
    }

    /**
     * The count the UI shows comes from here, not from the membership subscription, which the core
     * does not wake for the Element Call compat entry point - see item 7 in
     * `libraries/rustrtc/FEEDBACK.md`. Publishing it on the feed rather than on a timer is only sound
     * because a feed is what a membership change looks like from here; if this stops firing, the
     * roster silently freezes rather than erroring, so it is worth pinning down.
     */
    @Test
    fun `the core's member count is published after a membership feed`() = runTest {
        val manager = RecordingSessionManager(coreMemberCount = 2uL)
        val memberCount = MutableStateFlow(0)

        startFeeder(
            manager,
            stateEvents = MutableStateFlow(listOf(aMemberStateEvent())),
            elementCallCompat = MatrixRtcElementCallCompat.STATE_EVENTS,
            memberCount = memberCount,
        )

        assertThat(memberCount.value).isEqualTo(2)
    }

    /**
     * Every mode, in one test, because the failure this guards was the count being wired into two of
     * the three feeds and forgotten in the third - and the third was the default. Each mode looked
     * correct on its own: the missing one already logged how many memberships it had fed, so the count
     * appeared covered while the number on screen sat at zero for every call anyone actually placed.
     * A per-mode test would have passed.
     */
    @Test
    fun `the member count is published in every compatibility mode`() = runTest {
        MatrixRtcElementCallCompat.entries.forEach { compat ->
            val memberCount = MutableStateFlow(0)

            startFeeder(
                RecordingSessionManager(coreMemberCount = 2uL),
                // Both sources supplied, so whichever one this mode reads has something to feed.
                stickyEvents = MutableStateFlow(listOf(aMemberStickyEvent())),
                stateEvents = MutableStateFlow(listOf(aMemberStateEvent())),
                elementCallCompat = compat,
                memberCount = memberCount,
            )

            assertThat(memberCount.value).isEqualTo(2)
        }
    }

    /**
     * A null count is the core saying it holds no session for the slot, which after a join should not
     * happen. Whatever it means, it is not "the call emptied": overwriting a good count with zero
     * would take a working call's roster to nobody.
     */
    @Test
    fun `a member count the core cannot answer leaves the last one alone`() = runTest {
        val manager = RecordingSessionManager(coreMemberCount = null)
        val memberCount = MutableStateFlow(3)

        startFeeder(
            manager,
            stateEvents = MutableStateFlow(listOf(aMemberStateEvent())),
            elementCallCompat = MatrixRtcElementCallCompat.STATE_EVENTS,
            memberCount = memberCount,
        )

        assertThat(memberCount.value).isEqualTo(3)
    }

    /**
     * [joinedMemberIds] is who the room holds. Defaults to a loaded list because a membership feed waits
     * for one - see [RoomStateFeeder.awaitRoomMembers] - so a flow that never emits feeds nothing at all,
     * which is a distinct scenario rather than the neutral one.
     */
    private fun TestScope.startFeeder(
        manager: RtcSessionManagerHandleInterface,
        stickyEvents: MutableStateFlow<List<ElementCallStickyEvent>> = MutableStateFlow(emptyList()),
        stateEvents: MutableStateFlow<List<ElementCallRoomStateEvent>> = MutableStateFlow(emptyList()),
        elementCallCompat: MatrixRtcElementCallCompat = MatrixRtcElementCallCompat.OFF,
        joinedMemberIds: Flow<List<UserId>> = flowOf(listOf(A_USER_ID)),
        slotId: String? = A_SLOT_ID,
        memberCount: MutableStateFlow<Int> = MutableStateFlow(0),
    ) {
        RoomStateFeeder(
            manager = manager,
            room = FakeElementCallMatrixRoom(
                joinedMemberIds = joinedMemberIds,
                stickyEvents = stickyEvents,
                stateEventsResult = { stateEvents },
            ),
            ownUserId = A_USER_ID,
            scope = backgroundScope,
            elementCallCompat = elementCallCompat,
            slotId = slotId,
            memberCount = memberCount,
        ).apply {
            start()
            // Split in production because the join between the two is what fixes the dialect
            // memberships are read in; nothing here depends on the gap.
            startMemberships()
        }
        runCurrent()
    }

    private fun aMemberStateEvent(
        stateKey: String = A_STATE_KEY,
        contentJson: String = """{"memberships":[{"membershipID":"$stateKey","expires":3600000}]}""",
        timestampMs: Long? = 1_700_000_000_000L,
    ) = ElementCallRoomStateEvent(
        eventType = MatrixRtcEventTypes.MEMBER_ELEMENT_CALL_STATE_UNSTABLE,
        stateKey = stateKey,
        sender = A_USER_ID,
        contentJson = contentJson,
        eventId = EventId("\$aStateEventId"),
        timestampMs = timestampMs,
    )

    private fun aMemberStickyEvent(
        memberId: String = A_MEMBER_ID,
        contentJson: String = """{"slot_id":"m.call","member":{"id":"$memberId","membership":"join"}}""",
    ) = ElementCallStickyEvent(
        sender = A_USER_ID,
        eventType = MatrixRtcEventTypes.MEMBER_UNSTABLE,
        stickyKey = memberId,
        eventId = EventId("\$anEventId"),
        expiresAtMs = 0L,
        eventJson = """{"content":$contentJson}""",
        // Cleartext, so the core is told so: the mapper only vouches for departures.
        encryptionInfo = null,
    )

    private companion object {
        const val A_MEMBER_ID = "aMemberId"

        /** The shape that generation keys its membership by: `_{user}_{device}_{application}{call_id}`. */
        const val A_STATE_KEY = "_@alice:server.org_AAAA_m.call"
    }
}

/** Shaped as MSC4143 requires, so the feeder's malformed-slot warning stays out of the test output. */
private const val A_SLOT_ID = "m.call#ROOM"
