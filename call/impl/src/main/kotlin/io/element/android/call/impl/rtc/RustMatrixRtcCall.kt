/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import android.content.Context
import io.element.android.call.api.ElementCallDispatchers
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.rtc.FfiLeaveReason
import org.matrix.rtc.FfiLeaveSessionParams
import org.matrix.rtc.MediaSessionConfig
import org.matrix.rtc.RtcCall
import org.matrix.rtc.connectMediaSession
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

internal class RustMatrixRtcCall(
    private val rtcCall: RtcCall,
    override val roomId: RoomId,
    private val callScope: CoroutineScope,
    private val dispatchers: ElementCallDispatchers,
    private val ffiDispatcher: CoroutineDispatcher,
    /** Passed straight to the media session, which needs one to open the camera. */
    private val context: Context,
) : MatrixRtcCall {
    override val slotId: String = rtcCall.slotId()
    override val memberId: String = rtcCall.memberId()

    private val _members = MutableStateFlow(emptyList<MatrixRtcMembership>())
    override val members: StateFlow<List<MatrixRtcMembership>> = _members

    override val memberCount: StateFlow<Int> = _members
        .map { it.size }
        .stateIn(callScope, SharingStarted.Eagerly, 0)

    private var mediaSession: RustMatrixRtcMediaSession? = null

    /** Set by the leave or the close, whichever comes first: after either there is nothing left to leave. */
    private val hasEnded = AtomicBoolean(false)

    /** Follow the slot's roster: the current one first, then each change, until the room shuts down. */
    fun start() {
        callScope.launch {
            val subscription = rtcCall.subscribeMembershipSnapshots()
            subscription.use {
                while (true) {
                    val snapshot = runCatchingExceptions { it.next() }
                        .onFailure { error -> Timber.w(error, "MatrixRTC: membership subscription for $roomId/$slotId failed") }
                        .getOrNull()
                        ?: break
                    val members = snapshot.map { member -> member.map() }
                    Timber.i("MatrixRTC: ${members.size} member(s) in $roomId/$slotId: ${members.map { member -> member.memberId }}")
                    _members.value = members
                }
            }
        }
    }

    override suspend fun connectMedia(): Result<MatrixRtcMediaSession> = runCatchingExceptions {
        mediaSession?.let { return@runCatchingExceptions it }
        // The core takes the focus from the join and the account and tokens from the backend.
        val ffiMediaSession = withContext(ffiDispatcher) { connectMediaSession(rtcCall, MediaSessionConfig(stability = null)) }
        RustMatrixRtcMediaSession(
            // The id the core minted at join time, which is also what the media roster reports us under.
            localMemberId = memberId,
            mediaSession = ffiMediaSession,
            // A child of the call scope, so leaving the call tears the media down with it.
            callScope = callScope.childScope(dispatchers.io, "MatrixRtcMediaSession-$roomId-$slotId"),
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
            // Hanging up and shutting the room down both leave.
            Timber.d("MatrixRTC: already left $roomId/$slotId")
            return Result.success(Unit)
        }
        mediaSession?.disconnect()
        mediaSession = null
        return withContext(ffiDispatcher) {
            runCatchingExceptions {
                rtcCall.leave(FfiLeaveSessionParams(leaveReason = reason?.let { FfiLeaveReason(code = it.code, reason = it.reason) }))
            }.onFailure {
                Timber.w(it, "MatrixRTC: failed to leave $roomId/$slotId")
            }
        }.also { callScope.cancel() }
    }

    override fun close() {
        // Stops the roster reader and any media. Does not leave: a dropped call expires through its delayed leave.
        hasEnded.set(true)
        mediaSession?.close()
        mediaSession = null
        callScope.cancel()
        rtcCall.close()
    }
}
