/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrixrtc.impl

import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.DeviceId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.exception.ClientException
import io.element.android.libraries.matrix.api.exception.ErrorKind
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrixrtc.impl.bridge.MatrixRtcBridgeException
import io.element.android.libraries.matrixrtc.impl.bridge.MatrixRtcDelayedEventAction
import io.element.android.libraries.matrixrtc.impl.bridge.MatrixRtcRoomBridge
import io.element.android.libraries.matrixrtc.impl.bridge.widget.MatrixRtcBridgeRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import uniffi.matrix_rtc_ffi.CommandSenderCallback
import uniffi.matrix_rtc_ffi.CommandSenderException
import uniffi.matrix_rtc_ffi.FfiToDeviceDelivery
import uniffi.matrix_rtc_ffi.FfiToDeviceRecipient

/**
 * The outbound half of the bridge: everything the RTC core wants to put on the wire.
 *
 * Two destinations. What the released SDK exposes goes straight to it: the state event through
 * [JoinedRoom.sendRawStateEvent]. What it does not - delayed events, sticky events, to-device - goes
 * through the room's [MatrixRtcRoomBridge], looked up in [bridges] because a bridge lives as long as a
 * call in its room while this sender lives as long as the session.
 *
 * Every callback suspends, so each one maps straight onto the suspending call it needs, with no
 * blocking bridge in between. They still run on [commandDispatcher] rather than on whichever thread
 * uniffi resumes us on, so a slow send cannot hold a thread the core wanted for something else.
 * Failures become [CommandSenderException.SendException] so the core can react rather than seeing a
 * crash cross the FFI boundary.
 */
internal class MatrixRtcCommandSender(
    private val client: MatrixClient,
    private val commandDispatcher: CoroutineDispatcher,
    private val roomProvider: suspend (RoomId) -> JoinedRoom?,
    private val bridges: MatrixRtcBridgeRegistry,
) : CommandSenderCallback {
    /**
     * @return the event id the homeserver assigned, which the core relates an MSC4075 notification to - or
     * whatever the bridge can report in its place. Through the widget driver this whole call is refused as
     * [CommandSenderException.NotSupported]: MSC4354 has no widget API, so only the state-event compat mode
     * works until the SDK exposes sticky events (`FEEDBACK.md`, "Widget-driver stopgap").
     */
    override suspend fun sendStickyEvent(roomId: String, eventType: String, contentJson: String, durationMs: ULong): String {
        // The core emits the unstable membership type itself, so the string goes out verbatim, and the
        // lifetime is the core's to choose, not ours: it is the side that knows when it will next
        // refresh the membership, and a duration picked here could expire first and drop us out of a
        // session we are still in.
        return command("sendStickyEvent($eventType, ${durationMs}ms)", classify = ::bridgeFailure) {
            bridge(roomId).sendStickyEvent(eventType, contentJson, durationMs).getOrThrow()
        }
    }

    /**
     * @return the event id the homeserver assigned. Unread for an `m.rtc.slot`, but in
     * [io.element.android.libraries.matrixrtc.api.MatrixRtcElementCallCompat.STATE_EVENTS] the membership itself
     * comes through here, and there it is what an MSC4075 notification relates to.
     */
    override suspend fun sendStateEvent(roomId: String, eventType: String, stateKey: String, contentJson: String): String {
        return command("sendStateEvent($eventType)") {
            room(roomId).sendRawStateEvent(eventType, stateKey, contentJson).getOrThrow().value
        }
    }

    /**
     * @return the MSC4140 delay id - not an event id, a delayed event has none until it fires - which
     * [restartDelayedEvent] and [cancelDelayedEvent] then take.
     *
     * Throwing does not fail the join: the core carries on and shortens the membership's lifetime instead, so a
     * homeserver without MSC4140 costs only the speed of the cleanup when a client dies. Hence
     * [delayedEventFailure] rather than the default classifier - it is what tells the core to stop asking.
     */
    override suspend fun sendDelayedEvent(roomId: String, eventType: String, contentJson: String, delayMs: ULong): String {
        return command("sendDelayedEvent($eventType)", classify = ::delayedEventFailure) {
            bridge(roomId).sendDelayedEvent(eventType, stateKey = null, contentJson, delayMs).getOrThrow()
        }
    }

    /**
     * The dead man's switch for a membership carried as *room state*, which is what
     * [io.element.android.libraries.matrixrtc.api.MatrixRtcElementCallCompat.STATE_EVENTS] publishes.
     *
     * Only ever called in that mode: the message-like [sendDelayedEvent] has no state key to send a
     * membership under, so the two are not interchangeable. Worth knowing that this is the better half
     * of the pair - a delayed *state* event with `{}` content genuinely empties our membership when it
     * fires, whereas a delayed sticky leave clears nothing from the sticky map.
     */
    override suspend fun sendDelayedStateEvent(
        roomId: String,
        eventType: String,
        stateKey: String,
        contentJson: String,
        delayMs: ULong,
    ): String {
        return command("sendDelayedStateEvent($eventType)", classify = ::delayedEventFailure) {
            bridge(roomId).sendDelayedEvent(eventType, stateKey, contentJson, delayMs).getOrThrow()
        }
    }

    override suspend fun cancelDelayedEvent(roomId: String, delayId: String) {
        command("cancelDelayedEvent", classify = ::delayedEventFailure) {
            bridge(roomId).updateDelayedEvent(delayId, MatrixRtcDelayedEventAction.CANCEL).getOrThrow()
        }
    }

    override suspend fun restartDelayedEvent(roomId: String, delayId: String) {
        // The action is the only thing separating this from the cancel above, and getting the two the
        // wrong way round would retire the membership the dead man's switch is meant to protect -
        // dropping us out of a call we are still in, minutes later, with nothing in the log to connect
        // cause to effect. Hence the test that pins each one to its action.
        command("restartDelayedEvent", classify = ::delayedEventFailure) {
            bridge(roomId).updateDelayedEvent(delayId, MatrixRtcDelayedEventAction.RESTART).getOrThrow()
        }
    }

    override suspend fun sendToDeviceMessage(
        recipients: List<FfiToDeviceRecipient>,
        messageType: String,
        contentJson: String,
    ): List<FfiToDeviceDelivery> {
        // The recipients belong in the log, not just the type. Whether the core addressed a member at
        // all is otherwise indistinguishable from a key that was sent and lost, and those are faults
        // on opposite sides of the FFI. Counting sends against the far end's received-key lines only
        // works if both name the same device.
        //
        // The key index goes in too, so this line can be read against our own "key index N imported"
        // lines on the same device: those say what our frame cryptor is encrypting with, this says what
        // we told the far end to decrypt with, and the pair being out of step is invisible otherwise.
        val index = EncryptionKeyMapper.outgoingKeyIndex(contentJson)
        val describedIndex = if (index == null) "" else " index $index"
        val describedRecipients = recipients.joinToString { "${it.userId}/${it.deviceId}" }
        // The core filters us out by user and device, so this should now be impossible. Named rather
        // than tolerated because it is a regression signal, not a case to handle: the homeserver drops
        // a to-device message a device addresses to itself, so such a recipient can only ever fail.
        recipients
            .filter { it.userId == client.sessionId.value && it.deviceId == client.deviceId.value }
            .forEach { Timber.w("MatrixRTC: to-device $messageType addressed to ourselves (${it.userId}/${it.deviceId})") }

        return command("sendToDeviceMessage($messageType$describedIndex to [$describedRecipients])", classify = ::bridgeFailure) {
            // The core does not say which room a key belongs to, and a to-device message is not scoped to
            // one anyway: any live bridge carries it. None live means no call, and no call has no keys to send.
            val bridge = bridges.any() ?: throw CommandSenderException.SendException("No live bridge to send $messageType through")
            // One send for the whole batch rather than one per recipient: the bridge takes the same
            // recipient map the core hands us, and reports back exactly who it could not serve. The
            // bridge encrypts: RTC media keys must never go out in the clear.
            val failures = bridge.sendToDeviceMessage(
                eventType = messageType,
                messages = recipients
                    .groupBy { UserId(it.userId) }
                    .mapValues { (_, userRecipients) ->
                        userRecipients.associate { DeviceId(it.deviceId) to contentJson }
                    },
            ).getOrThrow()

            if (failures.isNotEmpty()) {
                Timber.w("MatrixRTC: to-device $messageType not delivered to $failures")
            }
            // One verdict per recipient. A recipient reported as delivered is never re-sent to, and
            // one reported as failed is retried on the next rollout, so reporting a failure as a
            // success is how a member ends up permanently keyless.
            recipients.map { recipient ->
                val failed = failures[UserId(recipient.userId)]?.any { it.value == recipient.deviceId } == true
                FfiToDeviceDelivery(
                    userId = recipient.userId,
                    deviceId = recipient.deviceId,
                    error = if (failed) "Homeserver did not accept the message for this device" else null,
                )
            }
        }
    }

    private suspend fun room(roomId: String): JoinedRoom {
        return roomProvider(RoomId(roomId))
            ?: throw CommandSenderException.SendException("Not a joined room: $roomId")
    }

    private fun bridge(roomId: String): MatrixRtcRoomBridge {
        return bridges[RoomId(roomId)]
            ?: throw CommandSenderException.SendException("No live bridge for $roomId")
    }

    private suspend fun <T> command(
        description: String,
        // How a failure the core has not already been handed becomes one it can read. Everything is a
        // plain send failure except the delayed-event callbacks, which have a second verdict to report.
        classify: (Throwable, String) -> CommandSenderException = ::sendFailure,
        block: suspend () -> T,
    ): T {
        // Logged on entry, not just on failure: which callbacks the core actually uses, and with
        // which event types, is the main thing we are still learning about the FFI.
        Timber.i("MatrixRTC command: $description")
        return try {
            withContext(commandDispatcher) { block() }
        } catch (exception: CommandSenderException) {
            throw exception
        } catch (cancellation: CancellationException) {
            // Must stay a cancellation: dressing it up as a send failure would tell the core the
            // command was attempted and failed, when in fact the session it belongs to is gone.
            throw cancellation
        } catch (throwable: Throwable) {
            Timber.w(throwable, "MatrixRTC command failed: $description")
            throw classify(throwable, description)
        }
    }
}

private fun sendFailure(throwable: Throwable, description: String): CommandSenderException {
    return CommandSenderException.SendException("$description failed: ${throwable.message}")
}

/**
 * A bridge that cannot do something at all says so, and the core should hear the same: `NotSupported`
 * retires the operation for the session rather than retrying it. Anything else is a plain send failure.
 */
private fun bridgeFailure(throwable: Throwable, description: String): CommandSenderException {
    return when (throwable) {
        is MatrixRtcBridgeException.NotSupported -> CommandSenderException.NotSupported("$description: ${throwable.message}")
        else -> sendFailure(throwable, description)
    }
}

/**
 * Separates a homeserver that will never accept a delayed event from one that failed this once.
 *
 * [CommandSenderException.NotSupported] retires the dead man's switch for the rest of the session and keeps the
 * membership alive by its lifetime alone; a plain [CommandSenderException.SendException] degrades the same way but
 * re-probes periodically. So the cost of getting this wrong is only wasted requests in one direction - and in the
 * other, a homeserver that recovers is never asked again, which is why a transient failure must not land here.
 *
 * The verdict is read off the Matrix error code, whichever transport carried it: the bridge's own error for
 * the widget driver, the SDK's mapped `ClientException` for a future SDK-backed bridge. A bridge that is not
 * running, or that timed out, is a transient failure - the next attempt may well have a bridge.
 */
private fun delayedEventFailure(throwable: Throwable, description: String): CommandSenderException {
    if (throwable is MatrixRtcBridgeException.NotSupported) return bridgeFailure(throwable, description)
    val (errcode, message) = throwable.matrixError() ?: return sendFailure(throwable, description)
    val neverSupported = when (errcode) {
        // 404 M_UNRECOGNIZED: the endpoint is not implemented at all.
        ERRCODE_UNRECOGNIZED -> true
        // matrix.org answers 403 M_FORBIDDEN "Sending delayed events has been disallowed". Forbidden also covers a
        // genuine power-level rejection of a delayed *state* event, which is our fault rather than the
        // homeserver's and would come back the moment our power level changed - so only the message separates the
        // two, and a mismatch has to fall through to a retryable failure.
        ERRCODE_FORBIDDEN -> message?.contains("delayed event", ignoreCase = true) == true
        else -> false
    }
    if (!neverSupported) return sendFailure(throwable, description)
    Timber.w("MatrixRTC: homeserver does not support MSC4140 ($errcode), no dead man's switch this session")
    return CommandSenderException.NotSupported("$description refused by the homeserver: $message")
}

/** The Matrix error code and message a homeserver answered with, from either transport. */
private fun Throwable.matrixError(): Pair<String?, String?>? = when (this) {
    is MatrixRtcBridgeException.MatrixApi -> errcode to message
    is ClientException.MatrixApi -> kind.toErrcode() to message
    else -> null
}

private fun ErrorKind.toErrcode(): String? = when (this) {
    ErrorKind.Unrecognized -> ERRCODE_UNRECOGNIZED
    ErrorKind.Forbidden -> ERRCODE_FORBIDDEN
    else -> null
}

private const val ERRCODE_UNRECOGNIZED = "M_UNRECOGNIZED"
private const val ERRCODE_FORBIDDEN = "M_FORBIDDEN"
