/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import android.content.Context
import io.element.android.call.api.ElementCallDispatchers
import io.element.android.call.api.matrix.ElementCallMatrixTransport
import io.element.android.call.api.rtc.MatrixRtcCall
import io.element.android.call.api.rtc.MatrixRtcLeaveReason
import io.element.android.call.api.rtc.MatrixRtcMediaSession
import io.element.android.call.api.rtc.MatrixRtcMembership
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.impl.util.childScope
import io.element.android.call.impl.util.runCatchingExceptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.rtc.FfiLeaveReason
import org.matrix.rtc.FfiLeaveSessionParams
import org.matrix.rtc.MediaSessionConfig
import org.matrix.rtc.MembershipSnapshotSubscription
import org.matrix.rtc.RtcSessionManagerHandle
import org.matrix.rtc.connectMediaSession
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

internal class RustMatrixRtcCall(
    override val roomId: RoomId,
    override val slotId: String,
    override val memberId: String,
    /** The transport the call was joined on, which is where its media connects. */
    private val liveKit: RtcTransport.LiveKit,
    private val manager: RtcSessionManagerHandle,
    private val transport: ElementCallMatrixTransport,
    private val callScope: CoroutineScope,
    private val dispatchers: ElementCallDispatchers,
    private val ffiDispatcher: CoroutineDispatcher,
    /** Passed straight to the media session, which needs one to open the camera. */
    private val context: Context,
    /**
     * Written by [RoomStateFeeder] after each membership feed, which is the only moment the count can
     * change for a reason we can see. Shared rather than owned here so that nothing has to poll: the
     * call has no signal of its own to read the count on.
     */
    override val memberCount: StateFlow<Int>,
) : MatrixRtcCall {
    private val _members = MutableStateFlow(emptyList<MatrixRtcMembership>())
    override val members: StateFlow<List<MatrixRtcMembership>> = _members

    private var mediaSession: RustMatrixRtcMediaSession? = null

    /** Set by the leave or the close, whichever comes first: after either there is nothing left to leave. */
    private val hasEnded = AtomicBoolean(false)

    /**
     * Guarded together because closing races creating: `leave()` can arrive before
     * `subscribeMembershipSnapshots` has returned, and a subscription handed over after that point has
     * to be closed on arrival or its reader parks in `nextSnapshot()` with nothing left to wake it.
     */
    private val subscriptionLock = Any()
    private var membershipSubscription: MembershipSnapshotSubscription? = null
    private var isSubscriptionClosed = false

    /**
     * Suspends until the membership subscription exists, then leaves it reading in the background.
     *
     * The caller must not feed the core any membership before this returns. `nextSnapshot()` only
     * hands over what changes *after* the subscription is created, so a membership fed in the window
     * between joining and subscribing is never reported - and if nothing else changes afterwards, as
     * happens when a call is already running when we join it, the roster stays empty for the whole
     * call while the core itself knows perfectly well who is there. That is not hypothetical: it is
     * what "0 in call" next to a working two-way Element Call looked like.
     */
    suspend fun start() {
        val subscription = withContext(ffiDispatcher) {
            runCatchingExceptions { manager.subscribeMembershipSnapshots(roomId.value, slotId) }
                .onFailure { Timber.w(it, "MatrixRTC: cannot subscribe to memberships for $roomId/$slotId") }
                .getOrNull()
        }
        if (subscription == null) {
            // Null means the core has no session for this slot, which after a successful join
            // should be impossible - so it is worth a line rather than a silent empty roster.
            Timber.w("MatrixRTC: no membership subscription for $roomId/$slotId, membership will not update")
            return
        }
        if (!keepSubscription(subscription)) return
        readMemberships(subscription)
    }

    /**
     * Follow the core's membership projection for this slot.
     *
     * `nextSnapshot()` blocks the calling thread until the core has something new, which is why this
     * runs on the general IO dispatcher rather than [ffiDispatcher]: parking that single thread for
     * the length of a call would leave nothing to start `leave()`, the media connection or any feed
     * on, and the call would simply hang.
     */
    private fun readMemberships(subscription: MembershipSnapshotSubscription) {
        callScope.launch(dispatchers.io) {
            var hasReportedSnapshot = false
            while (isActive) {
                val snapshot = runCatchingExceptions { subscription.nextSnapshot() }
                    .onFailure { Timber.w(it, "MatrixRTC: membership subscription for $roomId/$slotId ended") }
                    .getOrNull()
                    ?: break
                val members = snapshot.map { it.map() }
                // Logged on change, next to the sticky feed line that says how many of those
                // memberships are joins and how many are leaves. A roster that tracks the whole
                // snapshot rather than its joins is the phantom-member bug, and the two lines
                // together are the whole proof.
                //
                // The first snapshot is logged even when it is empty and therefore not a change,
                // because "the core reported nobody" and "the core reported nothing at all" are
                // different faults that look identical in a roster that stays at zero.
                if (!hasReportedSnapshot || _members.value.map { it.memberId } != members.map { it.memberId }) {
                    hasReportedSnapshot = true
                    Timber.i("MatrixRTC: ${members.size} member(s) in $roomId/$slotId: ${members.map { it.memberId }}")
                }
                _members.value = members
            }
        }
    }

    override suspend fun connectMedia(): Result<MatrixRtcMediaSession> = runCatchingExceptions {
        mediaSession?.let { return@runCatchingExceptions it }

        val ffiMediaSession = withContext(ffiDispatcher) {
            connectMediaSession(
                manager,
                // No member id to pass: the core knows which membership this session joined as,
                // so the host can no longer hand it one that disagrees with the sticky event it published.
                MediaSessionConfig(
                    roomId = roomId.value,
                    slotId = slotId,
                    userId = transport.userId.value,
                    deviceId = transport.deviceId.value,
                    livekitServiceUrl = liveKit.serviceUrl,
                ),
                RustOpenIdTokenProvider(transport),
            )
        }

        // A child of the call scope, so leaving the call tears the media down with it.
        val mediaScope = callScope.childScope(dispatchers.io, "MatrixRtcMediaSession-$roomId-$slotId")
        RustMatrixRtcMediaSession(
            // The id the core minted at join time, which is also what the media roster reports us
            // under - so nothing here has to reconcile two spellings of ourselves.
            localMemberId = memberId,
            mediaSession = ffiMediaSession,
            callScope = mediaScope,
            ffiDispatcher = ffiDispatcher,
            context = context,
            dispatchers = dispatchers,
        ).also {
            mediaSession = it
            it.start()
            Timber.d("MatrixRTC: media connected for $roomId/$slotId")
        }
    }.onFailure {
        Timber.w(it, "MatrixRTC: failed to connect media for $roomId/$slotId")
    }

    override suspend fun leave(reason: MatrixRtcLeaveReason?): Result<Unit> {
        if (!hasEnded.compareAndSet(false, true)) {
            // Hanging up and shutting the room down both leave, and the core rejects the second
            // attempt as `not joined`.
            Timber.d("MatrixRTC: already left $roomId/$slotId")
            return Result.success(Unit)
        }
        mediaSession?.disconnect()
        mediaSession = null
        // Before the leave rather than after: a sticky snapshot arriving while we are leaving makes
        // the core create a fresh session for the slot, seeded with none of the room state we fed
        // the old one.
        closeMembershipSubscription()
        callScope.cancel()
        return withContext(ffiDispatcher) {
            runCatchingExceptions {
                manager.leave(
                    roomId.value,
                    slotId,
                    FfiLeaveSessionParams(
                        leaveReason = reason?.let { FfiLeaveReason(code = it.code, reason = it.reason) },
                    ),
                )
            }.onFailure {
                Timber.w(it, "MatrixRTC: failed to leave $roomId/$slotId")
            }
        }
    }

    override fun close() {
        // Stops the feed loops, the membership subscription and any media. Does not leave the
        // call: callers that want a clean departure must call leave() first.
        hasEnded.set(true)
        mediaSession?.close()
        mediaSession = null
        closeMembershipSubscription()
        callScope.cancel()
    }

    /** @return false if the call has already been closed, in which case the subscription is spent. */
    private fun keepSubscription(subscription: MembershipSnapshotSubscription): Boolean = synchronized(subscriptionLock) {
        if (isSubscriptionClosed) {
            subscription.close()
            return false
        }
        membershipSubscription = subscription
        return true
    }

    /**
     * Closing the handle is what unblocks the reader parked in `nextSnapshot()`; cancelling the
     * scope alone cannot, because that thread is inside a blocking FFI call and has no
     * cancellation point to reach.
     */
    private fun closeMembershipSubscription() = synchronized(subscriptionLock) {
        isSubscriptionClosed = true
        membershipSubscription?.close()
        membershipSubscription = null
    }
}
