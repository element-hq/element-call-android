/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrixrtc.impl

import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.RoomMembershipState
import io.element.android.libraries.matrix.api.room.roomMembers
import io.element.android.libraries.matrixrtc.api.MatrixRtcElementCallCompat
import io.element.android.libraries.matrixrtc.api.MatrixRtcEventTypes
import io.element.android.libraries.matrixrtc.impl.bridge.MatrixRtcRoomBridge
import io.element.android.libraries.matrixrtc.impl.bridge.MatrixRtcRoomStateEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import timber.log.Timber
import uniffi.matrix_rtc_ffi.RtcSessionManagerHandleInterface
import uniffi.matrix_rtc_ffi.LegacyStateMemberEvent as FfiLegacyStateMemberEvent

/**
 * The room-scoped half of the inbound bridge: keeps the RTC core's view of one room up to date.
 *
 * Everything runs on [scope], which is tied to the lifetime of the joined session. That is safe for
 * everything fed here because all of it is snapshot-shaped - a new subscriber is handed the current
 * sticky events, room state, members and encryption state rather than only what changes next, so
 * starting at join time loses nothing. [SessionStateFeeder] holds the feed that does not have that
 * property.
 *
 * Members and encryption come from the SDK through [room]; memberships, which the released SDK has
 * no feed for, come through [bridge].
 */
internal class RoomStateFeeder(
    private val manager: RtcSessionManagerHandleInterface,
    private val room: JoinedRoom,
    private val bridge: MatrixRtcRoomBridge,
    private val scope: CoroutineScope,
    private val elementCallCompat: MatrixRtcElementCallCompat,
    /** The slot we joined, for [updateMemberCount] only. Defaulted for tests; joining always passes it. */
    private val slotId: String? = null,
    /**
     * Where [updateMemberCount] publishes what the core answered, for
     * [io.element.android.libraries.matrixrtc.api.MatrixRtcSession.memberCount] to expose.
     *
     * Here rather than in the session because this is the only place that knows *when* the membership
     * changed: every change we can act on arrives as a feed, and the count is already read once after
     * each one for the log. The session has no such signal - reading it there would mean a timer.
     */
    private val memberCount: MutableStateFlow<Int> = MutableStateFlow(0),
) {
    private val roomId: RoomId = room.roomId

    /**
     * Completed once the core has been told who is in the room. See [awaitRoomMembers].
     */
    private val roomMembersFed = CompletableDeferred<Unit>()

    /**
     * Everything the core needs *before* a join. [startMemberships] carries the rest.
     *
     * The split is not cosmetic. The core needs the room's members to accept a membership event at
     * all - a sender it cannot place in the room is excluded as `SenderNotInRoom` - so this half has
     * to run first. The membership half has to run second, because the compatibility mode is fixed by
     * the join and decides how a membership is read: fed beforehand, the first snapshot would be
     * parsed as MSC4143 whatever mode we are in, and an Element Call peer would stay invisible until
     * something happened to make the room emit again.
     */
    fun start() {
        feedRoomMembers()
        feedRoomEncryption()
        // onRoomSlotsReceived is intentionally not fed: the Rust SDK exposes no source of
        // MSC4143 slot state yet, so the core falls back to its default slot handling. That also
        // means the slot condition stays unenforced, which is what makes a call into a room whose
        // other participant is Element Call possible at all - no generation of it publishes
        // `m.rtc.slot`, so a truthful "no open slots" would resolve the session closed and project
        // out every member, ourselves included.
    }

    /**
     * Start feeding memberships. Call once the session has joined, see [start].
     *
     * Nothing is missed by subscribing late: the SDK seeds each new subscriber with the sticky events
     * currently live, and with the current room state, rather than only with what changes next.
     *
     * Each of these waits on [awaitRoomMembers] before it subscribes, which is the other half of the
     * ordering [start] describes.
     */
    fun startMemberships() {
        when (elementCallCompat) {
            MatrixRtcElementCallCompat.OFF -> feedSpecStickyEvents()
            MatrixRtcElementCallCompat.STICKY_EVENTS -> feedRawMemberships()
            MatrixRtcElementCallCompat.STATE_EVENTS -> feedStateMemberships()
        }
        if (elementCallCompat != MatrixRtcElementCallCompat.STATE_EVENTS) {
            // The two sticky modes are wired up but starved: no bridge feeds sticky events until the SDK
            // exposes MSC4354, so a call joined in either mode sees nobody. Said once here, next to the
            // choice, rather than left to be inferred from a roster that stays empty.
            Timber.w("MatrixRTC: $elementCallCompat needs MSC4354 sticky events, which the bridge for $roomId cannot feed; no membership will be fed")
        }
    }

    /**
     * The spec-current path: we take the MSC4143 content apart and hand the core flat fields.
     *
     * The SDK listener always emits the complete set of live sticky events, so we can use the
     * snapshot entry point and never have to diff. It *replaces* what the core holds for the room: a
     * member absent from the list is gone, and an empty list clears the room. That is deliberate here
     * - feeding a shrunken set is how an expired membership departs - so unlike the room member feed,
     * an empty snapshot must still be handed over rather than filtered out as "not loaded yet".
     */
    private fun feedSpecStickyEvents() {
        memberStickyEvents()
            .map { events -> events.mapNotNull { StickyEventMapper.map(roomId, it) } }
            .distinctUntilChanged()
            .onEach { events ->
                // The membership goes in the line, not just the member id: a snapshot of five is
                // only alarming if they are all joins, and a departure the core ignored looks
                // identical to a phantom member unless you can see which it was.
                val summary = events.joinToString { "${it.memberId}=${it.membership ?: "?"}" }
                Timber.i("MatrixRTC: feeding ${events.size} sticky event(s) for $roomId: [$summary]")
                feed("sticky events") { manager.setCurrentStickyState(roomId.value, events) }
                // Easy to forget here and nowhere else, because this is the one feed whose own log
                // line already says how many memberships it handed over - so the count looked
                // covered while the number the UI shows stayed at zero for every call in the default
                // compatibility mode.
                updateMemberCount("after feeding ${events.size} sticky event(s)")
            }
            .onStart { awaitRoomMembers() }
            .launchIn(scope)
    }

    /**
     * The compatibility path: raw content, parsed in the library.
     *
     * `setCurrentMembership` takes both membership sources in one call because it replaces the
     * room's whole membership. Fed separately, each call would wipe the other's members and the
     * roster would flicker between the two halves of the call - so the legacy list belongs here, even
     * though it is always empty in this mode: the two generations are mutually exclusive, and
     * [feedStateMemberships] is the one that fills it.
     */
    private fun feedRawMemberships() {
        memberStickyEvents()
            .map { events -> events.mapNotNull { event -> StickyEventMapper.mapRaw(event)?.let { it to StickyEventMapper.slotIdOf(event) } } }
            .distinctUntilChanged()
            .onEach { mapped ->
                // Sender, slot and encryption per event, not just a count. The count alone cannot
                // separate the three ways a peer goes missing from the roster while its media plays
                // perfectly well: an event we never fed because the type filter dropped it, one whose
                // slot id is not ours and so belongs to a different session, and one we fed that the
                // core then discarded - which in an encrypted room is what happens to a membership we
                // could not vouch for.
                val summary = mapped.joinToString { (event, slotId) ->
                    "${event.sender}/${event.senderDeviceId ?: "?"} slot=$slotId ${event.eventType} encrypted=${event.wasEncrypted}"
                }
                Timber.i("MatrixRTC: feeding ${mapped.size} raw membership(s) for $roomId ($elementCallCompat): [$summary]")
                feed("raw memberships") { manager.setCurrentMembership(roomId.value, mapped.map { it.first }, emptyList()) }
                updateMemberCount("after feeding ${mapped.size} membership(s)")
            }
            .onStart { awaitRoomMembers() }
            .launchIn(scope)
    }

    /**
     * Accept both spellings: we publish the unstable type, other clients may too, and ruma reads
     * either.
     *
     * A sticky event of any other type is dropped here, before anything else can see it - so the
     * types that were present but rejected are logged, or a peer publishing under a third spelling
     * would be indistinguishable from one that never published at all.
     */
    private fun memberStickyEvents() = bridge.stickyEvents()
        .map { events ->
            val (members, others) = events.partition { it.eventType in MatrixRtcEventTypes.MEMBER_TYPES }
            if (others.isNotEmpty()) {
                Timber.i("MatrixRTC: ignoring ${others.size} non-membership sticky event(s) in $roomId: ${others.map { it.eventType }.distinct()}")
            }
            members
        }

    /**
     * The pre-MSC4354 path: the membership is room state, and the library parses the dialect.
     *
     * A single subscription covers both spellings of the type. The bridge resolves the string we pass
     * the way ruma's `StateEventType` does, which declares `m.call.member` as an *alias* of
     * `org.matrix.msc3401.call.member`, so both collapse into one bucket - and subscribing to each
     * separately would feed the same members twice over, with each call wiping the other's list.
     * The unstable spelling is the one to pass: it is ruma's canonical name, so it survives the alias
     * being dropped, where the stable one would quietly resolve to a custom type and read an empty
     * bucket.
     *
     * **An empty snapshot is never fed, and the reason is not the obvious one.** Room state is only
     * ever replaced, never removed, so a departure in this generation is a *present* event with `{}`
     * content re-sent to the same state key - the list keeps its length, and the last participant
     * leaving still leaves one entry behind. An empty list therefore cannot mean "everyone left"; it
     * means nobody ever published here, or the bucket has not synced yet. Both are absence of
     * knowledge, and `setCurrentMembership` replaces rather than merges, so handing one over states
     * that the call is deserted, ourselves included. Live testing against Element Call showed what
     * follows: the roster drops to zero, the core stops refreshing a session it believes we are not
     * in, and ~20s later the delayed state event fires and empties our membership while the call is
     * still up. The far end sees us join and vanish; the give-away is `cancelDelayedEvent` answering
     * 404 at hangup, because the dead man's switch had already gone off. Suppressing a true empty
     * costs far less in return - the core resolves each membership's expiry from `created_ts` or
     * `origin_server_ts` and drops it on its own.
     *
     * For the same reason nothing here filters on content: a `{}` event is the departure, not junk.
     */
    private fun feedStateMemberships() {
        memberStateEvents()
            // Sorted because the SDK re-reads the whole state on every store wake, far more often
            // than a membership actually changes, and nothing promises a stable order between reads.
            // Unsorted, a reshuffle would defeat distinctUntilChanged and turn each redundant read
            // into a full membership replacement.
            .map { events -> events.map { it.toLegacyStateMemberEvent() }.sortedBy { it.stateKey } }
            .filter { events ->
                if (events.isEmpty()) {
                    Timber.i("MatrixRTC: not feeding an empty state membership snapshot for $roomId, it would read as a deserted call")
                }
                events.isNotEmpty()
            }
            .distinctUntilChanged()
            .onEach { events ->
                // Whether our own membership has echoed back yet goes in the line: "the peer is here
                // and we are not" and "nobody is here" are different faults, and a count cannot tell
                // them apart. Same for the departures, which are the entries the core will remove.
                val ownStateKey = events.count { it.stateKey.contains(room.sessionId.value) }
                val departures = events.count { it.contentJson.isBlank() || it.contentJson == "{}" }
                Timber.i(
                    "MatrixRTC: feeding ${events.size} state membership(s) for $roomId " +
                        "(ours=$ownStateKey, departures=$departures): [${events.joinToString { "${it.sender}@${it.stateKey}" }}]"
                )
                feed("state memberships") { manager.setCurrentMembership(roomId.value, emptyList(), events) }
                updateMemberCount("after feeding ${events.size} state membership(s)")
            }
            .onStart { awaitRoomMembers() }
            .launchIn(scope)
    }

    /**
     * Accept both spellings, as [memberStickyEvents] does, and for the same reason.
     *
     * Redundant in principle - we subscribe to one type and the SDK collapses the alias into it - but
     * the type that comes back is the raw one from the wire, so this is also where a bucket that
     * turned out to hold something else would show up rather than silently becoming a membership.
     */
    private fun memberStateEvents() = bridge.stateEvents(MatrixRtcEventTypes.MEMBER_ELEMENT_CALL_STATE_UNSTABLE)
        .map { events ->
            val (members, others) = events.partition { it.eventType in MatrixRtcEventTypes.MEMBER_ELEMENT_CALL_STATE_TYPES }
            if (others.isNotEmpty()) {
                Timber.i("MatrixRTC: ignoring ${others.size} non-membership state event(s) in $roomId: ${others.map { it.eventType }.distinct()}")
            }
            members
        }

    /**
     * Four fields across, with the content handed over untouched for the library to parse - the same
     * bargain [StickyEventMapper.mapRaw] strikes, and for the same reason.
     *
     * A missing timestamp becomes 0, which the core reads as "long expired". That is strictly better
     * than dropping the event: a content carrying its own `created_ts` is unaffected and stays a
     * usable membership, and one that is not lands where dropping would have put it anyway. Reaching
     * for the wall clock instead would be worse than both - it would resurrect a genuinely expired
     * membership as a phantom member. The warning matters more than the value: the SDK only omits a
     * timestamp for the stripped state of a room we are merely invited to, which we cannot be in
     * while joined to a call in it, so seeing one falsifies an assumption this whole feed rests on.
     */
    private fun MatrixRtcRoomStateEvent.toLegacyStateMemberEvent(): FfiLegacyStateMemberEvent {
        if (timestampMs == null) {
            Timber.w("MatrixRTC: state membership $stateKey in $roomId has no timestamp, treating it as long expired")
        }
        return FfiLegacyStateMemberEvent(
            sender = sender.value,
            stateKey = stateKey,
            originServerTs = timestampMs?.toULong() ?: 0uL,
            contentJson = contentJson,
        )
    }

    /**
     * The core needs this to accept membership events at all: a sender it cannot place in the room
     * is excluded from the session as `SenderNotInRoom`.
     */
    private fun feedRoomMembers() {
        scope.launch {
            // membersStateFlow stays at Unknown until this is called, so without it we would never
            // have anyone to feed and every membership candidate would stay excluded.
            room.updateMembers()
        }
        room.membersStateFlow
            .mapNotNull { it.roomMembers() }
            .map { members ->
                members
                    .filter { it.membership == RoomMembershipState.JOIN }
                    .map { it.userId.value }
            }
            // A room we are joined to always contains us, so an empty list is never the truth - it is
            // Pending handing back its empty `prevRoomMembers` before the first load finishes. Feeding
            // it tells the core the room is deserted, which excludes every membership as
            // `SenderNotInRoom` and rotates the media key on the way out and again on the way back in.
            .filter { it.isNotEmpty() }
            .distinctUntilChanged()
            .onEach { userIds ->
                Timber.i("MatrixRTC: feeding ${userIds.size} joined member(s) for $roomId")
                feed("room members") { manager.onRoomMembersReceived(roomId.value, userIds) }
                roomMembersFed.complete(Unit)
            }
            .launchIn(scope)
    }

    /**
     * Hold a membership feed until the core knows who is in the room.
     *
     * [feedRoomMembers] is asynchronous and nothing waits on it, while a membership source can hand
     * its first snapshot over the moment we subscribe - the state one always does. Lose that race and
     * the core excludes every membership in the snapshot as `SenderNotInRoom`, having nobody to place
     * the senders among.
     *
     * The sticky modes mostly get away with it, because something usually changes again soon and the
     * next snapshot lands after the members. [MatrixRtcElementCallCompat.STATE_EVENTS] does not: a
     * running Element Call rewrites its state membership only every few minutes, so a first snapshot
     * lost this way is an empty roster and no key distribution for that long, with nothing in the log
     * to say why.
     *
     * If the member list never loads we never feed a membership at all. That is the same outcome as
     * feeding one and having every entry rejected, minus a log line claiming we fed something.
     */
    private suspend fun awaitRoomMembers() = roomMembersFed.await()

    private fun feedRoomEncryption() {
        room.roomInfoFlow
            .map { it.isEncrypted }
            .distinctUntilChanged()
            .onEach { isEncrypted ->
                // Null means the SDK does not know yet; reporting false would let the core treat
                // cleartext membership as valid in a room that is actually encrypted.
                if (isEncrypted != null) {
                    Timber.i("MatrixRTC: feeding encryption=$isEncrypted for $roomId")
                    feed("room encryption") { manager.onRoomEncryptionReceived(roomId.value, isEncrypted) }
                }
            }
            .launchIn(scope)
    }

    /**
     * Ask the core directly how many members it holds for our slot, right after we have fed it.
     *
     * `memberCount` is a query rather than a subscription, so it reads the same session state that
     * key distribution reads and cannot be affected by whether anything gets *notified*. That is the
     * whole point: a roster stuck at zero while the core is demonstrably acting on a membership - it
     * picked that member as a media-key recipient - has two possible causes needing opposite fixes,
     * and a count here separates them. A count of 2 next to a snapshot of 0 means the projection is
     * right and `subscribeMembershipSnapshots` is not being woken; a count of 0 means the membership
     * never reached the projection at all and only the key path saw it.
     *
     * Diagnostic only, and cheap - once per membership change, not on a timer.
     */
    private suspend fun updateMemberCount(context: String) {
        val slotId = slotId ?: run {
            // Joining always passes a slot, so reaching here means one was built without it and both
            // the count the UI shows and every count line in the log are silently gone for the call.
            Timber.w("MatrixRTC: no slot id for $roomId, cannot read the member count $context")
            return
        }
        runCatchingExceptions { manager.memberCount(roomId.value, slotId) }
            .onSuccess { count ->
                Timber.i("MatrixRTC: core reports ${count ?: "no"} member(s) for $roomId/$slotId $context")
                // Null is the core saying it holds no session for this slot, which after a join should
                // not happen. Nothing to publish either way, and holding the last good value beats
                // showing a zero to someone who is in a call.
                if (count != null) memberCount.value = count.toInt()
            }
            .onFailure { Timber.w(it, "MatrixRTC: cannot read the member count for $roomId/$slotId") }
    }

    /**
     * Hand something to the core without letting its failure reach the scope.
     *
     * Belt and braces rather than a known hazard: the core no longer arms timers on our thread, so
     * the panic this originally guarded is gone, but uniffi still surfaces anything that goes wrong
     * inside the core as an exception and uncaught it would kill the process from a background flow,
     * losing the log right where the interesting part is. Feeding is best-effort by nature: the next
     * snapshot supersedes whatever this one failed to deliver.
     */
    private suspend fun feed(what: String, block: suspend () -> Unit) {
        runCatchingExceptions { block() }.onFailure {
            Timber.e(it, "MatrixRTC: core rejected $what for $roomId")
        }
    }
}
