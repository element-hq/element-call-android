/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrixrtc.impl

import android.content.Context
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.coroutine.childScope
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.notification.RtcNotificationType
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.widget.MatrixWidgetSettings
import io.element.android.libraries.matrixrtc.api.MatrixRtcElementCallCompat
import io.element.android.libraries.matrixrtc.api.MatrixRtcNotify
import io.element.android.libraries.matrixrtc.api.MatrixRtcService
import io.element.android.libraries.matrixrtc.api.MatrixRtcSession
import io.element.android.libraries.matrixrtc.api.MatrixRtcTransport
import io.element.android.libraries.matrixrtc.impl.bridge.MatrixRtcRoomBridge
import io.element.android.libraries.matrixrtc.impl.bridge.widget.MatrixRtcBridgeRegistry
import io.element.android.libraries.matrixrtc.impl.bridge.widget.ToDeviceRelay
import io.element.android.libraries.matrixrtc.impl.bridge.widget.WidgetCapabilityGrant
import io.element.android.libraries.matrixrtc.impl.bridge.widget.WidgetMatrixBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import uniffi.matrix_rtc_ffi.FfiElementCallCompat
import uniffi.matrix_rtc_ffi.FfiJoinSessionParams
import uniffi.matrix_rtc_ffi.FfiNotificationType
import uniffi.matrix_rtc_ffi.FfiNotifyConfig
import uniffi.matrix_rtc_ffi.FfiTransportConfig
import uniffi.matrix_rtc_ffi.RtcSessionManagerHandle
import java.util.UUID

internal class RustMatrixRtcService(
    private val client: MatrixClient,
    private val dispatchers: CoroutineDispatchers,
    /** Only reaches as far as the camera, via the session and then the call. */
    private val context: Context,
    private val sessionCoroutineScope: CoroutineScope = client.sessionCoroutineScope,
) : MatrixRtcService {
    /**
     * FFI calls are *started* on a single thread. The core is internally synchronised and its
     * exported methods now suspend, so uniffi resumes them wherever it likes - what this buys is
     * that the order we hand things over in is the order they are picked up, and that no FFI call
     * is initiated from the main dispatcher.
     */
    private val ffiDispatcher = dispatchers.io.limitedParallelism(1)

    /**
     * Built by [start] and never rebuilt. One core for the whole Matrix session is the shape the library
     * is designed for: it is the side that is supposed to notice a call starting anywhere and tell us,
     * which a per-call handle could not do. It also means the core keeps whatever it has accumulated -
     * memberships, keys - across calls, so replacing it would silently discard all of it.
     *
     * That accumulation is a double edge: per-member key state surviving a leave is the current suspect
     * for the joiner sitting at MISSING_KEY on a second call in the same process.
     */
    private var managerOrNull: RtcSessionManagerHandle? = null
    private val startMutex = Mutex()

    private suspend fun manager(): RtcSessionManagerHandle {
        start()
        return checkNotNull(managerOrNull)
    }

    private val transportDiscovery = RtcTransportDiscovery(client)

    /**
     * The Matrix operations the released SDK does not expose go through one bridge per room in a call
     * (see [MatrixRtcRoomBridge]). The registry is how the session-long command sender finds the bridge
     * of the room a command is for, and the relay is how the session-long to-device feed hears from
     * whichever bridge is live. Both are part of the widget-driver stopgap.
     */
    private val bridges = MatrixRtcBridgeRegistry()
    private val toDeviceRelay = ToDeviceRelay()

    override suspend fun start() {
        // Mutually excluded rather than lazy because the side effects matter as much as the value: the
        // to-device subscription below must be established exactly once for the session, and two
        // callers racing here would either subscribe twice - feeding every key to the core twice -
        // or leave one of them holding a manager that is not the one calls are joined on.
        startMutex.withLock {
            if (managerOrNull != null) return
            val newManager = withContext(ffiDispatcher) {
                MatrixRtcFfi.ensureInitialized()
                RtcSessionManagerHandle().apply {
                    setCommandSender(
                        MatrixRtcCommandSender(
                            client = client,
                            commandDispatcher = dispatchers.io,
                            roomProvider = { roomId -> client.getJoinedRoom(roomId) },
                            bridges = bridges,
                        )
                    )
                }
            }
            managerOrNull = newManager
            SessionStateFeeder(
                manager = newManager,
                relay = toDeviceRelay,
                // The session scope, not a call scope: the whole point is to be subscribed between
                // calls, so this must outlive every join and leave.
                scope = sessionCoroutineScope,
            ).start()
            Timber.i("MatrixRTC: core started for ${client.sessionId}")
        }
    }

    override suspend fun discoverTransports(): Result<List<MatrixRtcTransport>> = transportDiscovery.discover()

    override suspend fun joinSession(
        roomId: RoomId,
        slotId: String,
        application: String,
        transport: MatrixRtcTransport?,
        elementCallCompat: MatrixRtcElementCallCompat,
        notify: MatrixRtcNotify?,
    ): Result<MatrixRtcSession> = runCatchingExceptions {
        // manager() starts the core if nothing has yet. Belt and braces: the session should already be
        // bridged from app launch, and starting here is too late to have caught keys sent before now.
        // It keeps a caller that forgot from getting a call with no core at all, and being idempotent
        // it costs nothing when start() already ran.
        val manager = manager()
        val room = client.getJoinedRoom(roomId) ?: error("Not a joined room: $roomId")
        warnIfSlotIdIsMalformed(slotId, application)

        // The bridge comes up before anything is fed or joined: the join arms the delayed event through
        // it, and the membership feed reads from it. It is the last thing to go down too, after the
        // leave - see RustMatrixRtcSession.onSessionEnded - so its scope is a sibling of the call's,
        // not a child.
        val bridge = openBridge(room)
        bridges.register(bridge)

        return@runCatchingExceptions runCatchingExceptions {
            joinWithBridge(manager, room, bridge, slotId, application, transport, elementCallCompat, notify)
        }.onFailure {
            bridges.unregister(roomId)
            bridge.stop()
        }.getOrThrow()
    }

    private suspend fun joinWithBridge(
        manager: RtcSessionManagerHandle,
        room: JoinedRoom,
        bridge: MatrixRtcRoomBridge,
        slotId: String,
        application: String,
        transport: MatrixRtcTransport?,
        elementCallCompat: MatrixRtcElementCallCompat,
        notify: MatrixRtcNotify?,
    ): MatrixRtcSession {
        val roomId = room.roomId

        // One scope per session: cancelling it stops every feed loop for that session at once.
        val scope = sessionCoroutineScope.childScope(dispatchers.io, "MatrixRtcSession-$roomId-$slotId")

        // The feeder writes it after every membership feed, the session exposes it. Held here because
        // it is the one thing the two halves of a join have to share: the feeder is what knows when
        // the membership changed, and the session is what the caller gets handed.
        val memberCount = MutableStateFlow(0)

        val feeder = RoomStateFeeder(
            manager = manager,
            room = room,
            bridge = bridge,
            scope = scope,
            // Passed in rather than read later, because it decides how a membership is parsed on the
            // way in. Feeding one dialect and joining in another is not an error but a silence: a
            // call that connects and in which nobody appears.
            elementCallCompat = elementCallCompat,
            slotId = slotId,
            memberCount = memberCount,
        )

        val memberId: String = withContext(ffiDispatcher) {
            // Room members and encryption first: without them the core excludes every membership
            // candidate as `SenderNotInRoom`. Memberships themselves come after the join, which is
            // what fixes the dialect they are read in - see RoomStateFeeder.start.
            feeder.start()

            // The core mints the member id and hands it back. It is the id every sticky event, media
            // roster entry and frame-encryption line is keyed by, so this one value ties our own logs
            // to the far end's - and it is what the media session reports itself under. Deriving one
            // locally is the thing MSC4143 forbids, so having the core own it end to end removes the
            // last place a host could get that wrong.
            manager.join(
                FfiJoinSessionParams(
                    userId = client.sessionId.value,
                    deviceId = client.deviceId.value,
                    roomId = roomId.value,
                    slotId = slotId,
                    application = application,
                    transport = transport.toFfi(),
                    canSubscribe = CAN_SUBSCRIBE,
                    keepAliveTimeoutMs = KEEP_ALIVE_TIMEOUT_MS,
                    // Both nulls defer to the core. For the sticky duration that also keeps a
                    // single side in charge of the membership lifetime - it hands us the duration
                    // to use with each sendStickyEvent, so there is nothing for us to choose here.
                    stickyDurationMs = null,
                    // Null means "follow whatever the slot prescribes" rather than choosing locally.
                    encryptionConfig = null,
                    elementCallCompat = elementCallCompat.toFfi(),
                    // Null joins quietly. The core suppresses the notification anyway once anyone
                    // else is in the session, so passing one for a call that turns out to be already
                    // running rings nobody.
                    notify = notify?.toFfi(),
                )
            )
        }
        Timber.d(
            "MatrixRTC: joined $roomId/$slotId as $application, member id $memberId, compat $elementCallCompat" +
                ", notify ${notify?.let { "${it.type} (${it.intent})" } ?: "none"}"
        )

        return RustMatrixRtcSession(
            roomId = roomId,
            slotId = slotId,
            localMemberId = memberId,
            manager = manager,
            client = client,
            sessionScope = scope,
            dispatchers = dispatchers,
            ffiDispatcher = ffiDispatcher,
            context = context,
            memberCount = memberCount,
            onSessionEnded = {
                // Only the bridge registered for this join: a later join in the same room has its own.
                if (bridges[roomId] === bridge) bridges.unregister(roomId)
                // Not on the call scope, which is already cancelled by the time the session ends.
                sessionCoroutineScope.launch { bridge.stop() }
            },
        ).also {
            // Subscribed before a single membership is fed, and `start` suspends until it is: the
            // subscription reports what changes after it exists, so anything fed in between is
            // invisible. Joining a call that is already running feeds the whole roster at once and
            // then goes quiet, so losing that first batch loses it for the rest of the call.
            it.start()
            feeder.startMemberships()
        }
    }

    /**
     * Temporary: the widget-driver stopgap (`FEEDBACK.md`, "Widget-driver stopgap"). Drives the SDK's
     * widget machine in-process for the operations the released bindings do not expose, and suspends
     * until it has negotiated its capabilities, so that the join finds a bridge ready to carry the
     * delayed event.
     *
     * The only place a bridge is made, and the one seam to unpick: an SDK-backed [MatrixRtcRoomBridge]
     * replaces the body of this function and nothing else in the service changes.
     */
    private suspend fun openBridge(room: JoinedRoom): MatrixRtcRoomBridge {
        val widgetId = "matrixrtc-${UUID.randomUUID()}"
        val driver = room.getWidgetDriver(
            widgetSettings = MatrixWidgetSettings(
                id = widgetId,
                // Off, so the machine opens with its `capabilities` request as soon as it runs rather
                // than waiting for a `content_loaded` that no web view will ever send.
                initAfterContentLoad = false,
                // Only has to parse: nothing is ever loaded from it.
                rawUrl = WIDGET_URL,
            ),
            capabilities = WidgetCapabilityGrant.capabilities,
        ).getOrThrow()
        // A sibling of the call scope, never its child: the leave cancels the call scope before it
        // reaches the core, and the core cancels the delayed event through the bridge from inside it.
        val bridgeScope = sessionCoroutineScope.childScope(dispatchers.io, "MatrixRtcBridge-${room.roomId}")
        val bridge = WidgetMatrixBridge(
            roomId = room.roomId,
            widgetId = widgetId,
            driver = driver,
            parentScope = bridgeScope,
        )
        // Before start(): a key arriving during negotiation must not find nobody listening.
        bridge.toDeviceMessages()
            .onEach { toDeviceRelay.publish(it) }
            .launchIn(bridgeScope)
        bridge.start().onFailure {
            bridge.stop()
            driver.close()
        }.getOrThrow()
        return bridge
    }

    /**
     * MSC4143 requires a slot id to start with `{applicationType}#`, and nothing we call enforces it.
     *
     * The core validates this in `openSlot`, which has no data source on Android so we never call it,
     * and `join` takes whatever it is given. A malformed slot id therefore produces a call that looks
     * entirely healthy from here - membership published, media connected, audio flowing - while a
     * conformant peer refuses the membership on sight and we never learn why. That is how `m.call`
     * survived until Element Call rejected it with "slot_id must start with m.call#".
     *
     * A warning rather than a failure: the library is the side that owns this rule, and refusing a
     * join it would accept means guessing at a policy that may not stay ours.
     */
    private fun warnIfSlotIdIsMalformed(slotId: String, application: String) {
        val required = "$application#"
        if (!slotId.startsWith(required)) {
            Timber.w(
                "MatrixRTC: slot id '$slotId' does not start with '$required', which MSC4143 requires - " +
                    "conformant peers will reject our membership while this side looks healthy"
            )
        }
    }

    private fun MatrixRtcElementCallCompat.toFfi(): FfiElementCallCompat = when (this) {
        MatrixRtcElementCallCompat.OFF -> FfiElementCallCompat.OFF
        MatrixRtcElementCallCompat.STICKY_EVENTS -> FfiElementCallCompat.STICKY_EVENTS
        MatrixRtcElementCallCompat.STATE_EVENTS -> FfiElementCallCompat.STATE_EVENTS
    }

    private fun MatrixRtcTransport?.toFfi(): FfiTransportConfig? = when (this) {
        null -> null
        is MatrixRtcTransport.LiveKit -> FfiTransportConfig(type = LIVEKIT, livekitServiceUrl = serviceUrl)
        // Advertise nothing rather than a transport we cannot actually publish on.
        is MatrixRtcTransport.Unsupported -> null
    }

    private fun MatrixRtcNotify.toFfi(): FfiNotifyConfig = FfiNotifyConfig(
        notificationType = when (type) {
            RtcNotificationType.RING -> FfiNotificationType.RING
            // The core spells the silent one NOTIFICATION where the receive side says NOTIFY; the two
            // enums mean the same thing, which is the whole reason this mapper exists.
            RtcNotificationType.NOTIFY -> FfiNotificationType.NOTIFICATION
        },
        // Lowercase because the wire value is, and it goes out verbatim as `m.call.intent`.
        intent = intent?.name?.lowercase(),
        // Null leaves the core's default in place rather than restating it here, so its cap and its
        // idea of how long a phone should ring stay in one place.
        lifetimeMs = lifetimeMs,
        mentionUserIds = mentionUserIds.map { it.value },
        mentionRoom = mentionRoom,
    )

    private companion object {
        const val LIVEKIT = "livekit"
        val CAN_SUBSCRIBE = listOf(LIVEKIT)

        /** The widget driver needs a URL that parses; it never loads it. */
        const val WIDGET_URL = "https://call.element.io/"

        /** How long the core waits before considering a silent member gone. */
        const val KEEP_ALIVE_TIMEOUT_MS = 20_000uL
    }
}
