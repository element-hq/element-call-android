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
import io.element.android.call.api.rtc.id.DeviceId
import io.element.android.call.api.rtc.id.EventId
import io.element.android.call.api.rtc.id.UserId
import io.element.android.call.test.A_ROOM_ID
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
            "org.matrix.msc2762.receive.state_event:org.matrix.msc3401.call.member",
            "org.matrix.msc3819.send.to_device:io.element.call.encryption_keys",
            "org.matrix.msc4157.send.delayed_event",
        )
        assertThat(granted).hasSize(13)
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

    // State

    @Test
    fun `state deltas become whole snapshots, through both doors, without duplicates`() = runTest {
        val driver = FakeWidgetDriver()
        val bridge = negotiatedBridge(driver)

        bridge.stateEvents(A_MEMBER_TYPE).test {
            // The initial read after negotiation: two members in one message, one snapshot.
            driver.deliver(
                toWidget(
                    UPDATE_STATE,
                    "s-1",
                    stateBatch(
                        stateEvent(ALICE_KEY, "\$alice1", ALICE, aMembership("ALICEDEV")),
                        stateEvent(BOB_KEY, "\$bob1", BOB, aMembership("BOBDEV")),
                    )
                )
            )
            val first = awaitItem()
            assertThat(first.map { it.stateKey }).containsExactly(ALICE_KEY, BOB_KEY)
            assertThat(first.single { it.stateKey == ALICE_KEY }.timestampMs).isEqualTo(A_TIMESTAMP)
            assertThat(first.single { it.stateKey == ALICE_KEY }.sender).isEqualTo(ALICE)

            // A state event in the timeline arrives as send_event and replaces the entry for its key.
            driver.deliver(toWidget(SEND_EVENT, "s-2", stateEvent(ALICE_KEY, "\$alice2", ALICE, aMembership("ALICEDEV", expires = 7_200_000))))
            val second = awaitItem()
            assertThat(second).hasSize(2)
            assertThat(second.single { it.stateKey == ALICE_KEY }.eventId).isEqualTo(EventId("\$alice2"))

            // The same change through the other door is not a change.
            driver.deliver(toWidget(UPDATE_STATE, "s-3", stateBatch(stateEvent(ALICE_KEY, "\$alice2", ALICE, aMembership("ALICEDEV", expires = 7_200_000)))))
            expectNoEvents()

            // A departure is a present event with empty content, and it stays in the snapshot.
            driver.deliver(toWidget(SEND_EVENT, "s-4", stateEvent(BOB_KEY, "\$bob2", BOB, buildJsonObject {})))
            val third = awaitItem()
            assertThat(third).hasSize(2)
            assertThat(third.single { it.stateKey == BOB_KEY }.contentJson).isEqualTo("{}")
        }
    }

    @Test
    fun `a late subscriber gets the current state at once, and nothing for a type with none`() = runTest {
        val driver = FakeWidgetDriver()
        val bridge = negotiatedBridge(driver)
        driver.deliver(toWidget(UPDATE_STATE, "s-1", stateBatch(stateEvent(ALICE_KEY, "\$alice1", ALICE, aMembership("ALICEDEV")))))

        bridge.stateEvents(A_MEMBER_TYPE).test {
            assertThat(awaitItem().map { it.stateKey }).containsExactly(ALICE_KEY)
            expectNoEvents()
        }
        // Never an empty list: the feeder would read it as a deserted call.
        bridge.stateEvents("m.room.topic").test {
            expectNoEvents()
        }
    }

    /**
     * Ruma reads `m.call.member` as an alias of the unstable name, so the machine lets both through and
     * the wire type can be either. Both land in the one bucket the feeder subscribes to, with the
     * spelling they arrived in; a genuinely different type does not.
     */
    @Test
    fun `both spellings of the member type share one bucket and other types stay out of it`() = runTest {
        val driver = FakeWidgetDriver()
        val bridge = negotiatedBridge(driver)

        bridge.stateEvents(A_MEMBER_TYPE).test {
            driver.deliver(
                toWidget(
                    UPDATE_STATE,
                    "s-1",
                    stateBatch(
                        stateEvent(ALICE_KEY, "\$alice1", ALICE, aMembership("ALICEDEV")),
                        stateEvent(BOB_KEY, "\$bob1", BOB, aMembership("BOBDEV"), type = MatrixRtcEventTypes.MEMBER_ELEMENT_CALL_STATE),
                        stateEvent("", "\$topic1", ALICE, buildJsonObject { put("topic", "hello") }, type = "m.room.topic"),
                    )
                )
            )
            val snapshot = awaitItem()
            assertThat(snapshot.map { it.stateKey }).containsExactly(ALICE_KEY, BOB_KEY)
            assertThat(snapshot.single { it.stateKey == BOB_KEY }.eventType).isEqualTo(MatrixRtcEventTypes.MEMBER_ELEMENT_CALL_STATE)
        }
    }

    @Test
    fun `feeds complete when the bridge stops`() = runTest {
        val driver = FakeWidgetDriver()
        val bridge = negotiatedBridge(driver)

        bridge.stateEvents(A_MEMBER_TYPE).test {
            bridge.toDeviceMessages().test {
                bridge.stop()
                awaitComplete()
            }
            awaitComplete()
        }
    }

    // To-device

    @Test
    fun `an encrypted to-device message is trusted and names its device from the content`() = runTest {
        val driver = FakeWidgetDriver()
        val bridge = negotiatedBridge(driver)

        bridge.toDeviceMessages().test {
            driver.deliver(toWidget(SEND_TO_DEVICE, "t-1", toDeviceData(BOB, encrypted = true, content = aLegacyKey(deviceId = "BOBDEV"))))
            val topLevel = awaitItem()
            assertThat(topLevel.eventType).isEqualTo(MatrixRtcEventTypes.ENCRYPTION_KEY_ELEMENT_CALL)
            assertThat(topLevel.senderId).isEqualTo(BOB)
            assertThat(topLevel.content).contains("\"keys\"")
            val info = topLevel.encryptionInfo!!
            assertThat(info.senderId).isEqualTo(BOB)
            assertThat(info.senderDeviceId).isEqualTo(DeviceId("BOBDEV"))
            assertThat(info.isSenderCrossSigned).isTrue()

            // Element Call has since moved the device inside `member`.
            driver.deliver(toWidget(SEND_TO_DEVICE, "t-2", toDeviceData(BOB, encrypted = true, content = aLegacyKey(memberDeviceId = "BOBDEV2"))))
            assertThat(awaitItem().encryptionInfo?.senderDeviceId).isEqualTo(DeviceId("BOBDEV2"))
        }
    }

    @Test
    fun `a cleartext to-device message carries no encryption info`() = runTest {
        val driver = FakeWidgetDriver()
        val bridge = negotiatedBridge(driver)

        bridge.toDeviceMessages().test {
            driver.deliver(toWidget(SEND_TO_DEVICE, "t-1", toDeviceData(BOB, encrypted = false, content = aLegacyKey(deviceId = "BOBDEV"))))
            assertThat(awaitItem().encryptionInfo).isNull()

            // No `encrypted` at all reads as cleartext too.
            driver.deliver(toWidget(SEND_TO_DEVICE, "t-2", toDeviceData(BOB, encrypted = null, content = aLegacyKey(deviceId = "BOBDEV"))))
            assertThat(awaitItem().encryptionInfo).isNull()
        }
    }

    /**
     * Element Call's key messages do not always name their device, and the core refuses a key whose
     * device it cannot match to the membership. The sender's one live membership names it - from its
     * content, or failing that from its state key - and a sender with several is left unresolved.
     */
    @Test
    fun `the sender device is inferred from the membership when the key names none`() = runTest {
        val driver = FakeWidgetDriver()
        val bridge = negotiatedBridge(driver)
        driver.deliver(
            toWidget(
                UPDATE_STATE,
                "s-1",
                stateBatch(
                    // Bob: the device is in the content.
                    stateEvent(BOB_KEY, "\$bob1", BOB, aMembership("BOBDEV")),
                    // Carol: content without memberships, the device is only in the state key.
                    stateEvent("_${CAROL.value}_CARDEV_m.call", "\$carol1", CAROL, buildJsonObject { put("application", "m.call") }),
                    // Dave: two live devices.
                    stateEvent("_${DAVE.value}_DAVE1_m.call", "\$dave1", DAVE, aMembership("DAVE1")),
                    stateEvent("_${DAVE.value}_DAVE2_m.call", "\$dave2", DAVE, aMembership("DAVE2")),
                    // Erin: departed, so no live membership to read.
                    stateEvent("_${ERIN.value}_ERINDEV_m.call", "\$erin1", ERIN, buildJsonObject {}),
                )
            )
        )

        bridge.toDeviceMessages().test {
            driver.deliver(toWidget(SEND_TO_DEVICE, "t-1", toDeviceData(BOB, encrypted = true, content = aLegacyKey())))
            assertThat(awaitItem().encryptionInfo?.senderDeviceId).isEqualTo(DeviceId("BOBDEV"))
            driver.deliver(toWidget(SEND_TO_DEVICE, "t-2", toDeviceData(CAROL, encrypted = true, content = aLegacyKey())))
            assertThat(awaitItem().encryptionInfo?.senderDeviceId).isEqualTo(DeviceId("CARDEV"))
            driver.deliver(toWidget(SEND_TO_DEVICE, "t-3", toDeviceData(DAVE, encrypted = true, content = aLegacyKey())))
            assertThat(awaitItem().encryptionInfo?.senderDeviceId).isNull()
            driver.deliver(toWidget(SEND_TO_DEVICE, "t-4", toDeviceData(ERIN, encrypted = true, content = aLegacyKey())))
            assertThat(awaitItem().encryptionInfo?.senderDeviceId).isNull()
        }
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
    fun `a to-device send nests user, device and content, and reports the failures per recipient`() = runTest {
        val driver = FakeWidgetDriver()
        val bridge = negotiatedBridge(driver)
        val messages = mapOf(
            BOB to mapOf(DeviceId("BOBDEV") to A_CONTENT, DeviceId("BOBDEV2") to A_CONTENT),
            CAROL to mapOf(DeviceId("CARDEV") to A_CONTENT),
        )

        val result = async { bridge.sendToDeviceMessage(MatrixRtcEventTypes.ENCRYPTION_KEY_ELEMENT_CALL, messages) }
        val sent = driver.awaitSent()
        assertThat(sent.string("action")).isEqualTo(SEND_TO_DEVICE)
        assertThat(sent.data().string("type")).isEqualTo(MatrixRtcEventTypes.ENCRYPTION_KEY_ELEMENT_CALL)
        val wireMessages = sent.data()["messages"]!!.jsonObject
        assertThat(wireMessages.keys).containsExactly(BOB.value, CAROL.value)
        assertThat(wireMessages[BOB.value]!!.jsonObject.keys).containsExactly("BOBDEV", "BOBDEV2")
        assertThat(wireMessages[BOB.value]!!.jsonObject["BOBDEV"]!!.jsonObject).isEqualTo(json.parseToJsonElement(A_CONTENT).jsonObject)
        driver.givenIncomingMessage(
            responseTo(
                sent,
                buildJsonObject {
                    putJsonObject("failures") {
                        putJsonArray(BOB.value) { add("BOBDEV2") }
                    }
                },
            )
        )

        assertThat(result.await().getOrThrow()).isEqualTo(mapOf(BOB to listOf(DeviceId("BOBDEV2"))))
    }

    /** The machine omits `failures` entirely when everyone was served. */
    @Test
    fun `a to-device send with no failures reports none`() = runTest {
        val driver = FakeWidgetDriver()
        val bridge = negotiatedBridge(driver)

        val result = async { bridge.sendToDeviceMessage(MatrixRtcEventTypes.ENCRYPTION_KEY_ELEMENT_CALL, mapOf(BOB to mapOf(DeviceId("BOBDEV") to A_CONTENT))) }
        driver.givenIncomingMessage(responseTo(driver.awaitSent(), buildJsonObject {}))

        assertThat(result.await().getOrThrow()).isEmpty()
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
    fun `a dying driver fails what is in flight and ends the feeds`() = runTest {
        val driver = FakeWidgetDriver()
        val bridge = negotiatedBridge(driver)

        bridge.stateEvents(A_MEMBER_TYPE).test {
            val result = async { bridge.sendDelayedEvent(A_MEMBER_TYPE, A_STATE_KEY, A_CONTENT, 8_000uL) }
            driver.awaitSent()

            driver.givenDriverStopped()

            assertThat(result.await().exceptionOrNull()).isInstanceOf(ElementCallMatrixException.NotRunning::class.java)
            awaitComplete()
        }
    }

    @Test
    fun `a send the driver refuses stops the bridge`() = runTest {
        val driver = FakeWidgetDriver()
        val bridge = negotiatedBridge(driver)
        driver.givenDriverStopped(keepIncomingOpen = true)

        val result = bridge.sendDelayedEvent(A_MEMBER_TYPE, A_STATE_KEY, A_CONTENT, 8_000uL)

        assertThat(result.exceptionOrNull()).isInstanceOf(ElementCallMatrixException.NotRunning::class.java)
        bridge.toDeviceMessages().test { awaitComplete() }
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

    private fun stateBatch(vararg events: JsonObject) = buildJsonObject {
        put("state", JsonArray(events.toList()))
    }

    private fun stateEvent(
        stateKey: String,
        eventId: String,
        sender: UserId,
        content: JsonObject,
        type: String = A_MEMBER_TYPE,
    ) = buildJsonObject {
        put("type", type)
        put("state_key", stateKey)
        put("sender", sender.value)
        put("event_id", eventId)
        put("origin_server_ts", A_TIMESTAMP)
        put("room_id", A_ROOM_ID.value)
        put("content", content)
    }

    private fun aMembership(deviceId: String, expires: Long = 3_600_000) = buildJsonObject {
        putJsonArray("memberships") {
            add(
                buildJsonObject {
                    put("application", "m.call")
                    put("device_id", deviceId)
                    put("expires", expires)
                }
            )
        }
    }

    private fun toDeviceData(sender: UserId, encrypted: Boolean?, content: JsonObject) = buildJsonObject {
        put("type", MatrixRtcEventTypes.ENCRYPTION_KEY_ELEMENT_CALL)
        put("sender", sender.value)
        put("content", content)
        if (encrypted != null) put("encrypted", encrypted)
    }

    private fun aLegacyKey(deviceId: String? = null, memberDeviceId: String? = null) = buildJsonObject {
        putJsonArray("keys") {
            add(
                buildJsonObject {
                    put("index", 0)
                    put("key", "c2VjcmV0")
                }
            )
        }
        put("room_id", A_ROOM_ID.value)
        if (deviceId != null) put("device_id", deviceId)
        if (memberDeviceId != null) {
            putJsonObject("member") {
                put("device_id", memberDeviceId)
            }
        }
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
