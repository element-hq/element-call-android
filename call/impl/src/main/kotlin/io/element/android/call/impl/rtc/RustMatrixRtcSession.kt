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
import io.element.android.call.api.rtc.MatrixRtcMembership
import io.element.android.call.api.rtc.MatrixRtcSession
import io.element.android.call.api.rtc.MatrixRtcTransport
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
import timber.log.Timber
import uniffi.matrix_rtc_ffi.FfiLeaveReason
import uniffi.matrix_rtc_ffi.FfiLeaveSessionParams
import uniffi.matrix_rtc_ffi.MediaSessionConfig
import uniffi.matrix_rtc_ffi.MembershipSnapshotSubscription
import uniffi.matrix_rtc_ffi.RtcSessionManagerHandle
import uniffi.matrix_rtc_ffi.connectMediaSession
import java.util.concurrent.atomic.AtomicBoolean

internal class RustMatrixRtcSession(
    override val roomId: RoomId,
    override val slotId: String,
    private val localMemberId: String,
    private val manager: RtcSessionManagerHandle,
    private val transport: ElementCallMatrixTransport,
    private val sessionScope: CoroutineScope,
    private val dispatchers: ElementCallDispatchers,
    private val ffiDispatcher: CoroutineDispatcher,
    /** Passed straight to the call, which needs one to open the camera. */
    private val context: Context,
    /**
     * Written by [RoomStateFeeder] after each membership feed, which is the only moment the count can
     * change for a reason we can see. Shared rather than owned here so that nothing has to poll: the
     * session has no signal of its own to read the count on.
     */
    override val memberCount: StateFlow<Int>,
    /**
     * Called once the session is over, after the leave when there is one. What the service hooks here is
     * the room bridge's teardown: it has to outlive `manager.leave`, because the core cancels the delayed
     * event from inside it and that cancel goes through the bridge.
     */
    private val onSessionEnded: () -> Unit,
) : MatrixRtcSession {
    private val _members = MutableStateFlow(emptyList<MatrixRtcMembership>())
    override val members: StateFlow<List<MatrixRtcMembership>> = _members

    private var call: RustMatrixRtcCall? = null
    private val hasLeft = AtomicBoolean(false)

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
        sessionScope.launch(dispatchers.io) {
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

    override suspend fun connectMedia(transport: MatrixRtcTransport.LiveKit): Result<MatrixRtcCall> = runCatchingExceptions {
        call?.let { return@runCatchingExceptions it }

        val mediaSession = withContext(ffiDispatcher) {
            connectMediaSession(
                manager,
                // No member id to pass: the core knows which membership this session joined as,
                // so the host can no longer hand it one that disagrees with the sticky event it published.
                MediaSessionConfig(
                    roomId = roomId.value,
                    slotId = slotId,
                    userId = this@RustMatrixRtcSession.transport.userId.value,
                    deviceId = this@RustMatrixRtcSession.transport.deviceId.value,
                    livekitServiceUrl = transport.serviceUrl,
                ),
                RustOpenIdTokenProvider(this@RustMatrixRtcSession.transport),
            )
        }

        // A child of the session scope, so leaving the session tears the media down with it.
        val callScope = sessionScope.childScope(dispatchers.io, "MatrixRtcCall-$roomId-$slotId")
        RustMatrixRtcCall(
            // The id the core minted at join time, which is also what the media roster reports us
            // under - so nothing here has to reconcile two spellings of ourselves.
            localMemberId = localMemberId,
            mediaSession = mediaSession,
            callScope = callScope,
            ffiDispatcher = ffiDispatcher,
            context = context,
            dispatchers = dispatchers,
        ).also {
            call = it
            it.start()
            Timber.d("MatrixRTC: media connected for $roomId/$slotId")
        }
    }.onFailure {
        Timber.w(it, "MatrixRTC: failed to connect media for $roomId/$slotId")
    }

    override suspend fun leave(reason: MatrixRtcLeaveReason?): Result<Unit> {
        if (!hasLeft.compareAndSet(false, true)) {
            // Hanging up and tearing the activity down both leave - deliberately, so that a swipe
            // from recents still departs - and the core rejects the second attempt as `not joined`.
            Timber.d("MatrixRTC: already left $roomId/$slotId")
            return Result.success(Unit)
        }
        call?.disconnect()
        call = null
        // Before the leave rather than after: a sticky snapshot arriving while we are leaving makes
        // the core create a fresh session for the slot, seeded with none of the room state we fed
        // the old one.
        closeMembershipSubscription()
        sessionScope.cancel()
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
        }.also {
            // After the leave, success or not: the leave itself is the last thing to go through the bridge.
            onSessionEnded()
        }
    }

    override fun close() {
        // Stops the feed loops, the membership subscription and any media. Does not leave the
        // session: callers that want a clean departure must call leave() first.
        call?.close()
        call = null
        closeMembershipSubscription()
        sessionScope.cancel()
        onSessionEnded()
    }

    /** @return false if the session has already been closed, in which case the subscription is spent. */
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
