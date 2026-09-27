/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.matrix

import io.element.android.call.api.ElementCallDispatchers
import io.element.android.call.api.matrix.ElementCallMatrixRoom
import io.element.android.call.api.matrix.ElementCallMatrixTransport
import io.element.android.call.api.matrix.ElementCallOpenIdToken
import io.element.android.call.api.matrix.ElementCallToDeviceMessage
import io.element.android.call.api.rtc.id.DeviceId
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.api.rtc.id.UserId
import io.element.android.call.matrix.temporary.widget.SdkWidgetDriver
import io.element.android.call.matrix.temporary.widget.WidgetCapabilityGrant
import io.element.android.call.matrix.temporary.widget.WidgetMatrixBridge
import io.element.android.call.matrix.util.childScope
import io.element.android.call.matrix.util.runCatchingExceptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.WidgetSettings
import java.util.UUID

/**
 * The turnkey [ElementCallMatrixTransport] over a Rust SDK [Client]: what a host that has an SDK client
 * uses, so that it never implements the port itself.
 *
 * One per Matrix session, alive between calls. Element X builds it from `RustMatrixClient.innerClient`
 * in its session scope; the sample app has none and runs on fakes.
 *
 * @param client the logged-in SDK client the session runs on.
 * @param sessionScope lives as long as the Matrix session. The per-room widget-driver bridges hang off
 * it as siblings of the call, because the leave still goes through the bridge after the call scope is
 * cancelled.
 * @param dispatchers where SDK calls run; the default is fine for anything but a test.
 */
class ElementCallSdkTransport(
    private val client: Client,
    private val sessionScope: CoroutineScope,
    private val dispatchers: ElementCallDispatchers = ElementCallDispatchers.Default,
) : ElementCallMatrixTransport {
    override val userId: UserId = UserId(client.userId())
    override val deviceId: DeviceId = DeviceId(client.deviceId())
    override val homeserverUrl: String = client.homeserver()

    override fun userIdServerName(): String = client.userIdServerName()

    override suspend fun getUrl(url: String): Result<String> = withContext(dispatchers.io) {
        runCatchingExceptions { client.getUrl(url).decodeToString() }.mapSdkFailure()
    }

    override suspend fun getOpenIdToken(): Result<ElementCallOpenIdToken> = withContext(dispatchers.io) {
        runCatchingExceptions {
            client.requestOpenidToken().let {
                ElementCallOpenIdToken(
                    accessToken = it.accessToken,
                    tokenType = it.tokenType,
                    matrixServerName = it.matrixServerName,
                    expiresInSeconds = it.expiresInSeconds.toLong(),
                )
            }
        }.mapSdkFailure()
    }

    override suspend fun openRoom(roomId: RoomId): Result<ElementCallMatrixRoom> = runCatchingExceptions {
        val room = client.getRoom(roomId.value) ?: error("Not a joined room: $roomId")
        SdkElementCallMatrixRoom(
            roomId = roomId,
            room = room,
            bridge = openBridge(room, roomId),
            dispatchers = dispatchers,
        )
    }.mapSdkFailure()

    override fun toDeviceMessages(eventTypes: Set<String>): Flow<ElementCallToDeviceMessage> = client.toDeviceMessageUpdates(eventTypes, dispatchers)

    /**
     * Encrypted, always: the SDK's send encrypts for every listed device, and a media key is never sent
     * in the clear. One send per distinct content, so the usual case - the same key to everyone - is one.
     */
    override suspend fun sendToDeviceMessage(eventType: String, messages: Map<UserId, Map<DeviceId, String>>): Result<Map<UserId, List<DeviceId>>> =
        withContext(dispatchers.io) {
            runCatchingExceptions {
                messages.groupedByContent()
                    .flatMap { (content, recipients) -> client.sendEncryptedToDeviceMessage(eventType, recipients, content).failures.toRecipients().entries }
                    .groupBy({ it.key }, { it.value })
                    .mapValues { (_, deviceIds) -> deviceIds.flatten() }
            }.mapSdkFailure()
        }

    /**
     * Temporary: drives the SDK's widget machine in-process for the operations the released bindings do
     * not expose - delayed events, and a room event that answers with its id - and suspends until it has
     * negotiated its capabilities, so that the join finds a bridge ready to carry the delayed event.
     *
     * The only place a bridge is made, and the one seam to unpick: when the bindings gain delayed events
     * and `Room.sendRaw` returns the event id, [SdkElementCallMatrixRoom] calls them directly and this
     * function and the bridge go. Room state and to-device already go to the SDK.
     */
    private suspend fun openBridge(room: Room, roomId: RoomId): WidgetMatrixBridge {
        val widgetId = "matrixrtc-${UUID.randomUUID()}"
        val driver = SdkWidgetDriver(
            widgetSettings = WidgetSettings(
                widgetId = widgetId,
                // Off, so the machine opens with its `capabilities` request as soon as it runs rather
                // than waiting for a `content_loaded` that no web view will ever send.
                initAfterContentLoad = false,
                // Only has to parse: nothing is ever loaded from it.
                rawUrl = WIDGET_URL,
            ),
            room = room,
            widgetCapabilitiesProvider = WidgetCapabilityGrant,
        )
        // A sibling of the call scope, never its child: the leave cancels the call scope before it
        // reaches the core, and the core cancels the delayed event through the bridge from inside it.
        val bridgeScope = sessionScope.childScope(dispatchers.io, "MatrixRtcBridge-$roomId")
        val bridge = WidgetMatrixBridge(
            roomId = roomId,
            widgetId = widgetId,
            driver = driver,
            parentScope = bridgeScope,
        )
        bridge.start().onFailure {
            bridge.stop()
            driver.close()
        }.getOrThrow()
        return bridge
    }

    private companion object {
        /** The widget driver needs a URL that parses; it never loads it. */
        const val WIDGET_URL = "https://call.element.io/"
    }
}
