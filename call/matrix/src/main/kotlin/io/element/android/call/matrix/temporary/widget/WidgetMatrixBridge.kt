/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

// Temporary: widget-driver stopgap. The released SDK bindings lack delayed events, a room-state feed
// and to-device messaging, but their widget driver implements all of them for Element Call web. This
// speaks the widget API to that driver in-process, with no web view. Delete this package once the
// bindings gain the entry points listed in `libraries/rustrtc/FEEDBACK.md`, "Widget-driver stopgap",
// and give the consumers an SDK-backed `ElementCallMatrixRoom`.

package io.element.android.call.matrix.temporary.widget

import io.element.android.call.matrix.util.runCatchingExceptions
import io.element.android.call.api.rtc.id.DeviceId
import io.element.android.call.api.rtc.id.EventId
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.api.rtc.id.UserId
import io.element.android.libraries.matrix.api.widget.MatrixWidgetDriver
import io.element.android.call.api.rtc.MatrixRtcEventTypes
import io.element.android.call.api.matrix.ElementCallMatrixException
import io.element.android.call.api.matrix.ElementCallDelayedEventAction
import io.element.android.call.api.matrix.ElementCallEventEncryptionInfo
import io.element.android.call.api.matrix.ElementCallMatrixRoom
import io.element.android.call.api.matrix.ElementCallRoomStateEvent
import io.element.android.call.api.matrix.ElementCallStickyEvent
import io.element.android.call.api.matrix.ElementCallToDeviceMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One widget driver for one room, as the RTC core's Matrix bridge.
 *
 * Wire protocol (verified against the SDK's widget machine): every message is
 * `{api, widgetId, requestId, action, data}`; a response echoes the request with a `response` key.
 * With `initAfterContentLoad` off the driver opens with a `capabilities` request, calls the
 * capabilities provider, confirms with `notify_capabilities`, then pushes the current room state.
 * Requests sent before that are silently dropped, and the driver keeps at most 15 unanswered
 * requests of its own, so everything it sends is answered inline.
 *
 * State arrives as deltas (`update_state` from sync, `send_event` for timeline-borne state) while the
 * core wants the full state on every tick. Room state is replace-only, a leave being a present `{}`
 * event, so the latest event per state key *is* the full state and the map below re-emits it whole.
 *
 * @param parentScope where the driver's loops and the feeds run. The bridge makes its own child of it
 * so [stop] can cancel the loops without cancelling the caller; a child of the client session scope
 * rather than of the call's, because the leave itself still goes through the bridge.
 */
internal class WidgetMatrixBridge(
    override val roomId: RoomId,
    private val widgetId: String,
    private val driver: MatrixWidgetDriver,
    parentScope: CoroutineScope,
    private val requestTimeout: Duration = 30.seconds,
) : ElementCallMatrixRoom {
    private enum class Phase { IDLE, NEGOTIATING, READY, STOPPED }

    private val json = Json { ignoreUnknownKeys = true }

    private val driverScope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext.job))

    private val phase = MutableStateFlow(Phase.IDLE)

    /** Guards every phase transition, so a response and a teardown never both settle the same request. */
    private val lifecycleMutex = Mutex()

    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()

    /** Canonical event type -> state key -> latest event: the current state of every type we receive. */
    private val stateByType = ConcurrentHashMap<String, MutableStateFlow<Map<String, ElementCallRoomStateEvent>>>()

    private val toDevice = MutableSharedFlow<ElementCallToDeviceMessage>(extraBufferCapacity = 64)

    /** Completed once and for all by [tearDown]; the feeds end on it. */
    private val stopped = CompletableDeferred<Unit>()

    // Lifecycle

    override suspend fun start(): Result<Unit> {
        val started = lifecycleMutex.withLock {
            if (phase.value != Phase.IDLE) {
                false
            } else {
                phase.value = Phase.NEGOTIATING
                true
            }
        }
        if (!started) return Result.failure(ElementCallMatrixException.NotRunning(roomId))
        Timber.i("WidgetBridge: starting for $roomId")

        // Receiving must be under way before the driver runs: its first request times out after 10 s,
        // and the channel behind the flow keeps anything that arrives before this collector is scheduled.
        driverScope.launch {
            driver.incomingMessages
                .onCompletion { cause ->
                    // A cancelled collector is our own stop(), which has already torn down.
                    if (cause !is CancellationException) driverStopped()
                }
                .collect { handleIncoming(it) }
        }
        // From a coroutine of our own scope: the driver launches its loops into whichever coroutine
        // calls run(), and they must live as long as the bridge, not as long as the caller.
        driverScope.launch { driver.run() }

        return when (withTimeoutOrNull(requestTimeout) { phase.first { it != Phase.NEGOTIATING } }) {
            Phase.READY -> Result.success(Unit)
            null -> {
                Timber.e("WidgetBridge: negotiation timed out for $roomId")
                lifecycleMutex.withLock { tearDown() }
                Result.failure(ElementCallMatrixException.Timeout("negotiation"))
            }
            else -> Result.failure(ElementCallMatrixException.NotRunning(roomId))
        }
    }

    override suspend fun stop() {
        val wasRunning = lifecycleMutex.withLock {
            if (phase.value == Phase.STOPPED) {
                false
            } else {
                tearDown()
                true
            }
        }
        if (!wasRunning) return
        Timber.i("WidgetBridge: stopping for $roomId")
        // The driver only stops once its handle is gone, and the pending recv() holds the handle: a
        // request the machine always answers makes that recv() return, after which nothing receives
        // again and the handle is released. Best effort - a driver that is already gone does not care.
        runCatchingExceptions { driver.send(envelope(UUID.randomUUID().toString(), SUPPORTED_API_VERSIONS, EMPTY)) }
        // Drops the machine's `run` and the recv loop; uniffi frees the Rust future on cancellation.
        driverScope.cancel()
        driver.close()
    }

    // Sends

    override suspend fun sendDelayedEvent(eventType: String, stateKey: String?, contentJson: String, delayMs: ULong): Result<String> {
        val content = parseObject(contentJson)
            ?: return Result.failure(ElementCallMatrixException.InvalidResponse("content is not a JSON object"))
        val data = buildJsonObject {
            put("type", eventType)
            if (stateKey != null) put("state_key", stateKey)
            put("content", content)
            // A number, not a string: the machine reads a u64.
            put("delay", delayMs.toLong())
        }
        return request(SEND_EVENT, data).mapCatching { response ->
            response.string("delay_id")
                ?: throw ElementCallMatrixException.InvalidResponse("no delay_id in the send_event response")
        }
    }

    override suspend fun updateDelayedEvent(delayId: String, action: ElementCallDelayedEventAction): Result<Unit> {
        val wireAction = when (action) {
            ElementCallDelayedEventAction.CANCEL -> "cancel"
            ElementCallDelayedEventAction.RESTART -> "restart"
        }
        val data = buildJsonObject {
            put("delay_id", delayId)
            put("action", wireAction)
        }
        return request(UPDATE_DELAYED_EVENT, data).map { }
    }

    override suspend fun sendToDeviceMessage(eventType: String, messages: Map<UserId, Map<DeviceId, String>>): Result<Map<UserId, List<DeviceId>>> {
        val wireMessages = buildJsonObject {
            for ((userId, devices) in messages) {
                put(
                    userId.value,
                    buildJsonObject {
                        for ((deviceId, contentJson) in devices) {
                            val content = parseObject(contentJson)
                                ?: return Result.failure(ElementCallMatrixException.InvalidResponse("content is not a JSON object"))
                            put(deviceId.value, content)
                        }
                    },
                )
            }
        }
        val data = buildJsonObject {
            put("type", eventType)
            put("messages", wireMessages)
        }
        return request(SEND_TO_DEVICE, data).map { response ->
            // Omitted entirely when nobody failed.
            val failures = response["failures"] as? JsonObject ?: return@map emptyMap()
            failures.entries.mapNotNull { (userId, devices) ->
                val deviceIds = (devices as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(::DeviceId) } ?: return@mapNotNull null
                runCatchingExceptions { UserId(userId) }.getOrNull()?.let { it to deviceIds }
            }.toMap()
        }
    }

    override suspend fun sendStickyEvent(eventType: String, contentJson: String, durationMs: ULong): Result<String> {
        return Result.failure(ElementCallMatrixException.NotSupported("MSC4354 sticky event $eventType"))
    }

    // Feeds

    override fun stickyEvents(): Flow<List<ElementCallStickyEvent>> {
        Timber.w("WidgetBridge: sticky events are not available through the widget driver, no sticky snapshot will be fed for $roomId")
        return emptyFlow()
    }

    override fun stateEvents(eventType: String): Flow<List<ElementCallRoomStateEvent>> {
        return stateFlow(canonicalType(eventType))
            // Never an empty list: the feeder reads that as "not synced".
            .filter { it.isNotEmpty() }
            .map { it.values.toList() }
            .untilStopped()
    }

    override fun toDeviceMessages(): Flow<ElementCallToDeviceMessage> = toDevice.untilStopped()

    /** Ends when the bridge does, so a collector is not left waiting on a driver that is gone. */
    private fun <T> Flow<T>.untilStopped(): Flow<T> = channelFlow {
        if (stopped.isCompleted) return@channelFlow
        val upstream = launch { collect { send(it) } }
        stopped.await()
        upstream.cancel()
    }

    // Requests

    private suspend fun request(action: String, data: JsonObject): Result<JsonObject> {
        val requestId = UUID.randomUUID().toString()
        val reply = CompletableDeferred<JsonObject>()
        val registered = lifecycleMutex.withLock {
            if (phase.value == Phase.READY) {
                pending[requestId] = reply
                true
            } else {
                false
            }
        }
        if (!registered) return Result.failure(ElementCallMatrixException.NotRunning(roomId))
        Timber.v("WidgetBridge: → $action $requestId")
        try {
            if (!driver.send(envelope(requestId, action, data))) {
                driverStopped()
                return Result.failure(ElementCallMatrixException.NotRunning(roomId))
            }
            return withTimeoutOrNull(requestTimeout) {
                try {
                    Result.success(reply.await())
                } catch (exception: ElementCallMatrixException) {
                    Result.failure(exception)
                }
            } ?: Result.failure(ElementCallMatrixException.Timeout(action))
        } finally {
            pending.remove(requestId)
        }
    }

    /**
     * Keys in this order, deliberately: the machine's request enum is tagged on `action` with `data`
     * as its content, and `data` arriving first makes serde buffer it, which its raw JSON fields
     * cannot be read from. A JSON object built here keeps insertion order when encoded.
     */
    private fun envelope(requestId: String, action: String, data: JsonObject): String = encode(
        buildJsonObject {
            put("api", FROM_WIDGET)
            put("widgetId", widgetId)
            put("requestId", requestId)
            put("action", action)
            put("data", data)
        }
    )

    // Incoming

    private suspend fun handleIncoming(raw: String) {
        if (phase.value == Phase.STOPPED) return
        val message = parseObject(raw) ?: run {
            Timber.w("WidgetBridge: ignoring a message that is not a JSON object")
            return
        }
        if (message.string("widgetId") != widgetId) {
            Timber.w("WidgetBridge: ignoring a message for another widget")
            return
        }
        val api = message.string("api")
        val action = message.string("action").orEmpty()
        val requestId = message.string("requestId").orEmpty()

        if (api == FROM_WIDGET && message.containsKey("response")) {
            handleResponse(message["response"], action, requestId)
            return
        }
        if (api != TO_WIDGET) return

        Timber.v("WidgetBridge: ← $action $requestId")
        val data = message["data"] as? JsonObject ?: EMPTY
        var response: JsonObject = EMPTY
        when (action) {
            CAPABILITIES -> response = buildJsonObject {
                putJsonArray("capabilities") { WidgetCapabilityGrant.capabilityStrings.forEach { add(it) } }
            }
            NOTIFY_CAPABILITIES -> didNegotiate(approved = (data["approved"] as? JsonArray)?.size ?: 0)
            UPDATE_STATE -> applyStateEvents((data["state"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty())
            // A state event in the timeline comes through here rather than as update_state.
            SEND_EVENT -> if (data.containsKey("state_key")) applyStateEvents(listOf(data))
            SEND_TO_DEVICE -> deliverToDevice(data)
            else -> Unit
        }

        // Answer everything, inline: the machine drops requests unanswered for 10 s and stops sending
        // beyond 15 pending. `response` goes last, after the echoed request.
        val echo = JsonObject(message + (RESPONSE to response))
        if (!driver.send(encode(echo))) {
            driverStopped()
        }
    }

    private fun handleResponse(response: JsonElement?, action: String, requestId: String) {
        val reply = pending.remove(requestId) ?: run {
            // Our own stop poke, or a request that already timed out.
            Timber.v("WidgetBridge: unmatched response to $action $requestId")
            return
        }
        val body = response as? JsonObject ?: run {
            reply.completeExceptionally(ElementCallMatrixException.InvalidResponse("the $action response is not a JSON object"))
            return
        }
        val error = body["error"] as? JsonObject
        if (error != null) {
            val message = error.string("message") ?: "unknown error"
            val matrixError = error["matrix_api_error"] as? JsonObject
            val errorBody = matrixError?.get("response") as? JsonObject
            Timber.w("WidgetBridge: $action $requestId failed: $message")
            reply.completeExceptionally(
                ElementCallMatrixException.MatrixApi(
                    errcode = errorBody?.string("errcode"),
                    httpStatus = matrixError?.int("http_status"),
                    message = message,
                )
            )
            return
        }
        reply.complete(body)
    }

    private suspend fun didNegotiate(approved: Int) {
        lifecycleMutex.withLock {
            if (phase.value != Phase.NEGOTIATING) return
            Timber.i("WidgetBridge: negotiated $approved capabilities for $roomId")
            phase.value = Phase.READY
        }
    }

    private suspend fun driverStopped() {
        lifecycleMutex.withLock {
            if (phase.value == Phase.STOPPED) return
            Timber.w("WidgetBridge: driver stopped for $roomId")
            tearDown()
        }
    }

    /** Fails everything in flight and ends every feed. Call under [lifecycleMutex]. */
    private fun tearDown() {
        phase.value = Phase.STOPPED
        for (requestId in pending.keys.toList()) {
            pending.remove(requestId)?.completeExceptionally(ElementCallMatrixException.NotRunning(roomId))
        }
        stopped.complete(Unit)
    }

    // State

    /**
     * State events arrived (initial read, sync state block or timeline): remember the latest per state
     * key and hand subscribers the whole state of each changed type once, however many events the
     * batch held. Emitting per event made the first tick show one member instead of two.
     */
    private fun applyStateEvents(events: List<JsonObject>) {
        val changes = mutableMapOf<String, MutableMap<String, ElementCallRoomStateEvent>>()
        for (event in events) {
            val mapped = event.toStateEvent() ?: continue
            val type = canonicalType(mapped.eventType)
            val held = stateByType[type]?.value?.get(mapped.stateKey)
            // The same change through both doors.
            if (mapped.eventId != null && held?.eventId == mapped.eventId) continue
            changes.getOrPut(type) { mutableMapOf() }[mapped.stateKey] = mapped
        }
        for ((type, perKey) in changes) {
            stateFlow(type).update { it + perKey }
        }
    }

    private fun stateFlow(canonicalType: String) = stateByType.getOrPut(canonicalType) { MutableStateFlow(emptyMap()) }

    /**
     * Ruma treats `m.call.member` as an alias of `org.matrix.msc3401.call.member`, so the machine's
     * filter lets both spellings through and the wire type can be either. One bucket for both, keyed
     * on the unstable name; the event itself keeps the spelling it arrived with.
     */
    private fun canonicalType(eventType: String): String = when (eventType) {
        in MatrixRtcEventTypes.MEMBER_ELEMENT_CALL_STATE_TYPES -> MatrixRtcEventTypes.MEMBER_ELEMENT_CALL_STATE_UNSTABLE
        else -> eventType
    }

    private fun JsonObject.toStateEvent(): ElementCallRoomStateEvent? {
        val eventType = string("type")
        val stateKey = string("state_key")
        val sender = string("sender")?.let { runCatchingExceptions { UserId(it) }.getOrNull() }
        val content = this["content"] as? JsonObject
        if (eventType == null || stateKey == null || sender == null || content == null) {
            Timber.w("WidgetBridge: ignoring a malformed state event")
            return null
        }
        return ElementCallRoomStateEvent(
            eventType = eventType,
            stateKey = stateKey,
            sender = sender,
            contentJson = encode(content),
            eventId = string("event_id")?.let { runCatchingExceptions { EventId(it) }.getOrNull() },
            timestampMs = (this["origin_server_ts"] as? JsonPrimitive)?.longOrNull,
        )
    }

    // To-device

    /**
     * The driver hands over `{type, content, sender, encrypted}` only: it has already dropped cleartext
     * in an encrypted room and attested the sender of an encrypted message, but reports neither the
     * sender's device nor whether it is cross-signed. The device is read from the key message itself,
     * or failing that from the sender's one live membership, and an encrypted message is taken as
     * cross-signed - the trust Element Call web gets through this same driver (FEEDBACK.md,
     * "Widget-driver stopgap").
     */
    private suspend fun deliverToDevice(data: JsonObject) {
        val eventType = data.string("type")
        val sender = data.string("sender")?.let { runCatchingExceptions { UserId(it) }.getOrNull() }
        val content = data["content"] as? JsonObject
        if (eventType == null || sender == null || content == null) {
            Timber.w("WidgetBridge: ignoring a malformed to-device message")
            return
        }
        val wasEncrypted = (data["encrypted"] as? JsonPrimitive)?.booleanOrNull ?: false
        val claimedDeviceId = claimedDeviceId(content)
        val deviceId = claimedDeviceId ?: membershipDeviceId(sender)
        if (claimedDeviceId == null) {
            // Field names only, never values: which shape of key message the peer speaks.
            Timber.i("WidgetBridge: $eventType from $sender names no device (fields: ${content.keys.sorted()}), inferred ${deviceId ?: "none"}")
        }
        toDevice.emit(
            ElementCallToDeviceMessage(
                eventType = eventType,
                senderId = sender,
                content = encode(content),
                encryptionInfo = if (wasEncrypted) {
                    ElementCallEventEncryptionInfo(
                        senderId = sender,
                        senderDeviceId = deviceId?.let(::DeviceId),
                        senderCurve25519Key = null,
                        isSenderCrossSigned = true,
                    )
                } else {
                    null
                },
            )
        )
    }

    /**
     * The device the key message claims to come from: Element Call has written it at the top level
     * (`device_id`) and, more recently, inside `member`.
     */
    private fun claimedDeviceId(content: JsonObject): String? {
        return content.string("device_id") ?: (content["member"] as? JsonObject)?.string("device_id")
    }

    /**
     * The device behind the user's live call membership, when there is exactly one. Element Call's key
     * messages do not always name their device, while the core refuses a key whose device it cannot
     * match to the membership.
     */
    private fun membershipDeviceId(userId: UserId): String? {
        val memberships = stateByType[MatrixRtcEventTypes.MEMBER_ELEMENT_CALL_STATE_UNSTABLE]?.value?.values
            ?.filter { it.sender == userId && it.contentJson != "{}" }
            ?: return null
        val deviceIds = mutableSetOf<String>()
        for (membership in memberships) {
            (parseObject(membership.contentJson)?.get("memberships") as? JsonArray)
                ?.filterIsInstance<JsonObject>()
                ?.forEach { entry -> entry.string("device_id")?.let(deviceIds::add) }
            if (deviceIds.isEmpty()) {
                deviceIdInStateKey(membership.stateKey, userId.value)?.let(deviceIds::add)
            }
        }
        return deviceIds.singleOrNull()
    }

    /**
     * Element Call keys its membership `_{user}_{device}_m.call` (or `{user}_{device}`, or just the
     * user); the device is whatever follows the user id.
     */
    private fun deviceIdInStateKey(stateKey: String, userId: String): String? {
        val key = stateKey.removePrefix("_")
        if (!key.startsWith("${userId}_")) return null
        return key.removePrefix("${userId}_").removeSuffix("_m.call").takeIf { it.isNotEmpty() }
    }

    // JSON

    private fun parseObject(raw: String): JsonObject? = try {
        json.parseToJsonElement(raw) as? JsonObject
    } catch (_: Exception) {
        null
    }

    private fun encode(element: JsonObject): String = json.encodeToString(JsonObject.serializer(), element)

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private companion object {
        val EMPTY = JsonObject(emptyMap())

        const val FROM_WIDGET = "fromWidget"
        const val TO_WIDGET = "toWidget"
        const val RESPONSE = "response"

        // fromWidget actions
        const val SEND_EVENT = "send_event"
        const val SEND_TO_DEVICE = "send_to_device"
        const val UPDATE_DELAYED_EVENT = "org.matrix.msc4157.update_delayed_event"
        const val SUPPORTED_API_VERSIONS = "supported_api_versions"

        // toWidget actions
        const val CAPABILITIES = "capabilities"
        const val NOTIFY_CAPABILITIES = "notify_capabilities"
        const val UPDATE_STATE = "update_state"
    }
}
