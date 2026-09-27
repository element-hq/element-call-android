/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.matrix.temporary.widget

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.matrix.ElementCallDelayedEventAction
import io.element.android.call.api.matrix.ElementCallMatrixException
import io.element.android.call.api.rtc.MatrixRtcEventTypes
import io.element.android.call.api.rtc.id.EventId
import io.element.android.call.api.rtc.id.UserId
import io.element.android.call.test.A_ROOM_ID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetMatrixBridgeTest {
    private val json = Json

    // Negotiation

    @Test
    fun `negotiation answers the capability strings and resolves start`() = runTest {
        val driver = FakeWidgetDriver()
        val bridge = createBridge(driver)
        val start = backgroundScope.async { bridge.start() }
        runCurrent()
        assertThat(driver.runCalledCount).isEqualTo(1)

        // Nothing goes out before the machine has confirmed our capabilities: it would be dropped unanswered.
        val early = bridge.sendDelayedEvent(A_MEMBER_TYPE, A_STATE_KEY, A_CONTENT, 8_000uL)
        assertThat(early.exceptionOrNull()).isInstanceOf(ElementCallMatrixException.NotRunning::class.java)

        val capabilitiesReply = driver.deliver(toWidget(CAPABILITIES, "cap-1"))
        assertThat(capabilitiesReply.string("requestId")).isEqualTo("cap-1")
        val granted = capabilitiesReply.response()["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertThat(granted).containsExactlyElementsIn(WidgetCapabilityGrant.capabilityStrings)
        assertThat(granted).containsAtLeast(
            "org.matrix.msc2762.send.state_event:org.matrix.msc3401.call.member",
            "org.matrix.msc2762.send.event:org.matrix.msc4075.rtc.notification",
            "org.matrix.msc4157.send.delayed_event",
        )
        // Nothing read: room state and to-device come from the SDK.
        assertThat(granted.filter { ".receive." in it || "to_device" in it }).isEmpty()
        assertThat(granted).hasSize(9)
        assertThat(start.isCompleted).isFalse()

        val notifyReply = driver.deliver(toWidget(NOTIFY_CAPABILITIES, "cap-2", approvedCapabilities()))
        assertThat(notifyReply.response()).isEmpty()
        assertThat(start.await().isSuccess).isTrue()
    }

    @Test
    fun `start fails when the driver stops before negotiating`() = runTest {
        val driver = FakeWidgetDriver()
        val bridge = createBridge(driver)
        val start = backgroundScope.async { bridge.start() }
        runCurrent()

        driver.givenDriverStopped()

        assertThat(start.await().exceptionOrNull()).isInstanceOf(ElementCallMatrixException.NotRunning::class.java)
    }

    @Test
    fun `start times out when nothing negotiates`() = runTest {
        val driver = FakeWidgetDriver()
        val bridge = createBridge(driver, requestTimeout = 5.seconds)

        val result = bridge.start()

        assertThat(result.exceptionOrNull()).isInstanceOf(ElementCallMatrixException.Timeout::class.java)
        // Torn down: nothing will be carried from now on.
        val late = bridge.sendDelayedEvent(A_MEMBER_TYPE, A_STATE_KEY, A_CONTENT, 8_000uL)
        assertThat(late.exceptionOrNull()).isInstanceOf(ElementCallMatrixException.NotRunning::class.java)
    }

    @Test
    fun `start is refused a second time`() = runTest {
        val driver = FakeWidgetDriver()
        val bridge = negotiatedBridge(driver)

        assertThat(bridge.start().exceptionOrNull()).isInstanceOf(ElementCallMatrixException.NotRunning::class.java)
    }

    // The driver's own requests

    /**
     * The machine keeps at most 15 unanswered requests of its own and drops what goes beyond, so every
     * request it makes is answered, whether or not the bridge does anything with it.
     */
    @Test
    fun `every driver request is echoed exactly once, unknown actions included`() = runTest {
        val driver = FakeWidgetDriver()
        negotiatedBridge(driver)
        val sentBefore = driver.sentMessages.size

        val openId = driver.deliver(toWidget("openid_credentials", "o-1", buildJsonObject { put("state", "allowed") }))
        val unknown = driver.deliver(toWidget("something_new", "n-1"))

        assertThat(openId.string("requestId")).isEqualTo("o-1")
        assertThat(openId.string("action")).isEqualTo("openid_credentials")
        assertThat(openId.response()).isEmpty()
        assertThat(unknown.string("requestId")).isEqualTo("n-1")
        assertThat(driver.sentMessages.size).isEqualTo(sentBefore + 2)
    }

    @Test
    fun `state and to-device pushed by the driver are answered and otherwise ignored`() = runTest {
        val driver = FakeWidgetDriver()
        negotiatedBridge(driver)
        val sentBefore = driver.sentMessages.size

        // Room state and to-device come from the SDK; a machine still pushing them must not be left waiting.
        val state = driver.deliver(toWidget(UPDATE_STATE, "s-1", buildJsonObject { put("state", JsonArray(emptyList())) }))
        val toDevice = driver.deliver(toWidget(SEND_TO_DEVICE, "t-1", buildJsonObject { put("type", MatrixRtcEventTypes.ENCRYPTION_KEY_ELEMENT_CALL) }))

        assertThat(state.response()).isEmpty()
        assertThat(toDevice.response()).isEmpty()
        assertThat(driver.sentMessages.size).isEqualTo(sentBefore + 2)
    }

    @Test
    fun `messages for another widget and malformed messages are ignored without stopping the bridge`() = runTest {
        val driver = FakeWidgetDriver()
        negotiatedBridge(driver)
        val sentBefore = driver.sentMessages.size

        driver.givenIncomingMessage(toWidget("update_state", "x-1", widgetId = "someone-else"))
        driver.givenIncomingMessage("this is not json")
        driver.givenIncomingMessage("[1, 2, 3]")
        // Still alive and still answering: this one is echoed, the three above were not.
        val echo = driver.deliver(toWidget("something_new", "n-1"))

        assertThat(echo.string("requestId")).isEqualTo("n-1")
        assertThat(driver.sentMessages.size).isEqualTo(sentBefore + 1)
    }

    // Our requests

    /**
     * The machine's request enum is tagged on `action` with `data` as its content; `data` first makes
     * serde buffer the payload, whose raw JSON fields then fail to read.
     */
    @Test
    fun `requests put action before data`() = runTest {
        val driver = FakeWidgetDriver()
        val bridge = negotiatedBridge(driver)

        backgroundScope.async { bridge.sendDelayedEvent(A_MEMBER_TYPE, A_STATE_KEY, A_CONTENT, 8_000uL) }
        val raw = driver.awaitSentMessage()

        assertThat(raw.indexOf("\"action\"")).isLessThan(raw.indexOf("\"data\""))
        assertThat(raw.indexOf("\"api\"")).isLessThan(raw.indexOf("\"action\""))
    }

    @Test
    fun `a delayed state event is a send_event with a state key and a numeric delay, answered by a delay id`() = runTest {
        val driver = FakeWidgetDriver()
        val bridge = negotiatedBridge(driver)

        val result = async { bridge.sendDelayedEvent(A_MEMBER_TYPE, A_STATE_KEY, A_CONTENT, 8_000uL) }
        val sent = driver.awaitSent()
        assertThat(sent.string("api")).isEqualTo("fromWidget")
        assertThat(sent.string("widgetId")).isEqualTo(WIDGET_ID)
        assertThat(sent.string("action")).isEqualTo(SEND_EVENT)
        val data = sent.data()
        assertThat(data.string("type")).isEqualTo(A_MEMBER_TYPE)
        assertThat(data.string("state_key")).isEqualTo(A_STATE_KEY)
        assertThat(data["content"]!!.jsonObject).isEqualTo(json.parseToJsonElement(A_CONTENT).jsonObject)
        assertThat(data["delay"]!!.jsonPrimitive.isString).isFalse()
        assertThat(data["delay"]!!.jsonPrimitive.content).isEqualTo("8000")
        driver.givenIncomingMessage(responseTo(sent, buildJsonObject { put("delay_id", "syd_abc") }))

        assertThat(result.await().getOrThrow()).isEqualTo("syd_abc")
    }

    /** The MSC4075 notification in the state-event compat mode: the send that makes a call ring. */
    @Test
    fun `a room event is a send_event with neither state key nor delay, answered by an event id`() = runTest {
        val driver = FakeWidgetDriver()
        val bridge = negotiatedBridge(driver)

        val result = async { bridge.sendRoomEvent(A_NOTIFICATION_TYPE, A_CONTENT) }
        val sent = driver.awaitSent()
        assertThat(sent.string("action")).isEqualTo(SEND_EVENT)
        val data = sent.data()
        assertThat(data.string("type")).isEqualTo(A_NOTIFICATION_TYPE)
        assertThat(data.containsKey("state_key")).isFalse()
        assertThat(data.containsKey("delay")).isFalse()
        assertThat(data["content"]!!.jsonObject).isEqualTo(json.parseToJsonElement(A_CONTENT).jsonObject)
        driver.givenIncomingMessage(responseTo(sent, buildJsonObject { put("event_id", "\$notified") }))

        assertThat(result.await().getOrThrow()).isEqualTo(EventId("\$notified"))
    }

    @Test
    fun `a room event answered without an event id is an invalid response`() = runTest {
        val driver = FakeWidgetDriver()
        val bridge = negotiatedBridge(driver)

        val result = async { bridge.sendRoomEvent(A_NOTIFICATION_TYPE, A_CONTENT) }
        driver.givenIncomingMessage(responseTo(driver.awaitSent(), buildJsonObject { put("delay_id", "syd_abc") }))

        assertThat(result.await().exceptionOrNull()).isInstanceOf(ElementCallMatrixException.InvalidResponse::class.java)
    }

    @Test
    fun `a delayed message-like event sends no state key`() = runTest {
        val driver = FakeWidgetDriver()
        val bridge = negotiatedBridge(driver)

        val result = async { bridge.sendDelayedEvent("m.rtc.notification", null, A_CONTENT, 8_000uL) }
        val sent = driver.awaitSent()
        assertThat(sent.data().containsKey("state_key")).isFalse()
        driver.givenIncomingMessage(responseTo(sent, buildJsonObject { put("delay_id", "syd_abc") }))

        assertThat(result.await().getOrThrow()).isEqualTo("syd_abc")
    }

    /** An event id back means the machine sent it right away, which is not what was asked. */
    @Test
    fun `a delayed event answered with an event id is an invalid response`() = runTest {
        val driver = FakeWidgetDriver()
        val bridge = negotiatedBridge(driver)

        val result = async { bridge.sendDelayedEvent(A_MEMBER_TYPE, A_STATE_KEY, A_CONTENT, 8_000uL) }
        driver.givenIncomingMessage(responseTo(driver.awaitSent(), buildJsonObject { put("event_id", "\$sentAnyway") }))

        assertThat(result.await().exceptionOrNull()).isInstanceOf(ElementCallMatrixException.InvalidResponse::class.java)
    }

    @Test
    fun `content that is not a JSON object is refused before anything is sent`() = runTest {
        val driver = FakeWidgetDriver()
        val bridge = negotiatedBridge(driver)
        val sentBefore = driver.sentMessages.size

        val result = bridge.sendDelayedEvent(A_MEMBER_TYPE, A_STATE_KEY, "\"a string\"", 8_000uL)

        assertThat(result.exceptionOrNull()).isInstanceOf(ElementCallMatrixException.InvalidResponse::class.java)
        assertThat(driver.sentMessages.size).isEqualTo(sentBefore)
    }

    /**
     * Cancel and restart, each pinned to its wire action: swapping them retires the membership the dead
     * man's switch protects, minutes later, with nothing tying cause to effect.
     */
    @Test
    fun `updateDelayedEvent sends cancel and restart under their own names`() = runTest {
        val driver = FakeWidgetDriver()
        val bridge = negotiatedBridge(driver)

        listOf(ElementCallDelayedEventAction.CANCEL to "cancel", ElementCallDelayedEventAction.RESTART to "restart").forEach { (action, wire) ->
            val result = async { bridge.updateDelayedEvent("syd_abc", action) }
            val sent = driver.awaitSent()
            assertThat(sent.string("action")).isEqualTo("org.matrix.msc4157.update_delayed_event")
            assertThat(sent.data().string("delay_id")).isEqualTo("syd_abc")
            assertThat(sent.data().string("action")).isEqualTo(wire)
            driver.givenIncomingMessage(responseTo(sent, buildJsonObject {}))

            assertThat(result.await().isSuccess).isTrue()
        }
    }

    @Test
    fun `a homeserver error carries its errcode, status and message`() = runTest {
        val driver = FakeWidgetDriver()
        val bridge = negotiatedBridge(driver)

        val result = async { bridge.sendDelayedEvent(A_MEMBER_TYPE, A_STATE_KEY, A_CONTENT, 8_000uL) }
        driver.givenIncomingMessage(
            responseTo(
                driver.awaitSent(),
                buildJsonObject {
                    putJsonObject("error") {
                        put("message", "Sending delayed events has been disallowed")
                        putJsonObject("matrix_api_error") {
                            put("http_status", 403)
                            putJsonObject("response") {
                                put("errcode", "M_FORBIDDEN")
                                put("error", "Sending delayed events has been disallowed")
                            }
                        }
                    }
                },
            )
        )

        val error = result.await().exceptionOrNull() as ElementCallMatrixException.MatrixApi
        assertThat(error.errcode).isEqualTo("M_FORBIDDEN")
        assertThat(error.httpStatus).isEqualTo(403)
        assertThat(error.message).isEqualTo("Sending delayed events has been disallowed")
    }

    @Test
    fun `an error without a Matrix body has no errcode`() = runTest {
        val driver = FakeWidgetDriver()
        val bridge = negotiatedBridge(driver)

        val result = async { bridge.updateDelayedEvent("syd_abc", ElementCallDelayedEventAction.CANCEL) }
        driver.givenIncomingMessage(
            responseTo(driver.awaitSent(), buildJsonObject { putJsonObject("error") { put("message", "Not enough permissions") } })
        )

        val error = result.await().exceptionOrNull() as ElementCallMatrixException.MatrixApi
        assertThat(error.errcode).isNull()
        assertThat(error.httpStatus).isNull()
        assertThat(error.message).isEqualTo("Not enough permissions")
    }

    @Test
    fun `an unanswered request times out`() = runTest {
        val driver = FakeWidgetDriver()
        val bridge = negotiatedBridge(driver, requestTimeout = 5.seconds)

        val result = async { bridge.sendDelayedEvent(A_MEMBER_TYPE, A_STATE_KEY, A_CONTENT, 8_000uL) }
        val sent = driver.awaitSent()

        assertThat(result.await().exceptionOrNull()).isInstanceOf(ElementCallMatrixException.Timeout::class.java)
        // A late answer is not mistaken for anyone else's, and the bridge carries on.
        driver.givenIncomingMessage(responseTo(sent, buildJsonObject { put("delay_id", "late") }))
        val next = async { bridge.updateDelayedEvent("late", ElementCallDelayedEventAction.CANCEL) }
        driver.givenIncomingMessage(responseTo(driver.awaitSent(), buildJsonObject {}))
        assertThat(next.await().isSuccess).isTrue()
    }

    @Test
    fun `a dying driver fails what is in flight`() = runTest {
        val driver = FakeWidgetDriver()
        val bridge = negotiatedBridge(driver)

        val result = async { bridge.sendDelayedEvent(A_MEMBER_TYPE, A_STATE_KEY, A_CONTENT, 8_000uL) }
        driver.awaitSent()

        driver.givenDriverStopped()

        assertThat(result.await().exceptionOrNull()).isInstanceOf(ElementCallMatrixException.NotRunning::class.java)
    }

    @Test
    fun `a send the driver refuses stops the bridge`() = runTest {
        val driver = FakeWidgetDriver()
        val bridge = negotiatedBridge(driver)
        driver.givenDriverStopped(keepIncomingOpen = true)

        val result = bridge.sendDelayedEvent(A_MEMBER_TYPE, A_STATE_KEY, A_CONTENT, 8_000uL)

        assertThat(result.exceptionOrNull()).isInstanceOf(ElementCallMatrixException.NotRunning::class.java)
        // Stopped, not just this send: the next one is refused without reaching the driver.
        val sentBefore = driver.sentMessages.size
        assertThat(bridge.sendRoomEvent(A_NOTIFICATION_TYPE, A_CONTENT).exceptionOrNull()).isInstanceOf(ElementCallMatrixException.NotRunning::class.java)
        assertThat(driver.sentMessages.size).isEqualTo(sentBefore)
    }

    // Stopping

    @Test
    fun `stop pokes the driver, closes it, refuses further requests, and is idempotent`() = runTest {
        val driver = FakeWidgetDriver()
        val bridge = negotiatedBridge(driver)

        bridge.stop()

        val poke = json.parseToJsonElement(driver.sentMessages.last()).jsonObject
        assertThat(poke.string("api")).isEqualTo("fromWidget")
        assertThat(poke.string("action")).isEqualTo("supported_api_versions")
        assertThat(driver.closeCalledCount).isEqualTo(1)
        val late = bridge.updateDelayedEvent("syd_abc", ElementCallDelayedEventAction.CANCEL)
        assertThat(late.exceptionOrNull()).isInstanceOf(ElementCallMatrixException.NotRunning::class.java)

        bridge.stop()
        assertThat(driver.closeCalledCount).isEqualTo(1)
    }

    // Sticky events

    @Test
    fun `sticky events are not bridged`() = runTest {
        val driver = FakeWidgetDriver()
        val bridge = negotiatedBridge(driver)
        val sentBefore = driver.sentMessages.size

        val result = bridge.sendStickyEvent(MatrixRtcEventTypes.MEMBER_UNSTABLE, A_CONTENT, 60_000uL)

        assertThat(result.exceptionOrNull()).isInstanceOf(ElementCallMatrixException.NotSupported::class.java)
        assertThat(driver.sentMessages.size).isEqualTo(sentBefore)
        bridge.stickyEvents().test { awaitComplete() }
    }

    // Helpers

    private fun TestScope.createBridge(driver: FakeWidgetDriver, requestTimeout: Duration = 30.seconds) = WidgetMatrixBridge(
        roomId = A_ROOM_ID,
        widgetId = WIDGET_ID,
        driver = driver,
        parentScope = backgroundScope,
        requestTimeout = requestTimeout,
    )

    /** A bridge past its handshake, as the driver runs it: capabilities asked, granted, confirmed. */
    private suspend fun TestScope.negotiatedBridge(driver: FakeWidgetDriver, requestTimeout: Duration = 30.seconds): WidgetMatrixBridge {
        val bridge = createBridge(driver, requestTimeout)
        val start = backgroundScope.async { bridge.start() }
        driver.deliver(toWidget(CAPABILITIES, "cap-1"))
        driver.deliver(toWidget(NOTIFY_CAPABILITIES, "cap-2", approvedCapabilities()))
        assertThat(start.await().isSuccess).isTrue()
        return bridge
    }

    /** Hands the bridge a driver message and returns the bridge's answer to it. */
    private suspend fun FakeWidgetDriver.deliver(message: String): JsonObject {
        givenIncomingMessage(message)
        return json.parseToJsonElement(awaitSentMessage()).jsonObject
    }

    private suspend fun FakeWidgetDriver.awaitSent(): JsonObject = json.parseToJsonElement(awaitSentMessage()).jsonObject

    private fun toWidget(action: String, requestId: String, data: JsonObject = buildJsonObject {}, widgetId: String = WIDGET_ID): String {
        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("api", "toWidget")
                put("widgetId", widgetId)
                put("requestId", requestId)
                put("action", action)
                put("data", data)
            },
        )
    }

    private fun responseTo(request: JsonObject, response: JsonObject): String {
        return json.encodeToString(JsonObject.serializer(), JsonObject(request + ("response" to response)))
    }

    private fun approvedCapabilities() = buildJsonObject {
        putJsonArray("requested") { WidgetCapabilityGrant.capabilityStrings.forEach { add(it) } }
        putJsonArray("approved") { WidgetCapabilityGrant.capabilityStrings.forEach { add(it) } }
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun JsonObject.data(): JsonObject = this["data"]!!.jsonObject

    private fun JsonObject.response(): JsonObject = this["response"]!!.jsonObject

    private companion object {
        const val WIDGET_ID = "matrixrtc-test-widget"
        const val CAPABILITIES = "capabilities"
        const val NOTIFY_CAPABILITIES = "notify_capabilities"
        const val UPDATE_STATE = "update_state"
        const val SEND_EVENT = "send_event"
        const val SEND_TO_DEVICE = "send_to_device"

        const val A_MEMBER_TYPE = MatrixRtcEventTypes.MEMBER_ELEMENT_CALL_STATE_UNSTABLE
        const val A_NOTIFICATION_TYPE = "org.matrix.msc4075.rtc.notification"
        const val A_CONTENT = """{"application":"m.call","memberships":[]}"""
        const val A_TIMESTAMP = 1_700_000_000_000L

        val ALICE = UserId("@alice:example.org")
        val BOB = UserId("@bob:example.org")
        val CAROL = UserId("@carol:example.org")
        val DAVE = UserId("@dave:example.org")
        val ERIN = UserId("@erin:example.org")
        const val A_STATE_KEY = "_@alice:example.org_ALICEDEV_m.call"
        const val ALICE_KEY = A_STATE_KEY
        const val BOB_KEY = "_@bob:example.org_BOBDEV_m.call"
    }
}
