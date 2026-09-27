/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

// Temporary: widget-driver stopgap. The released SDK bindings lack delayed events and a room event send
// that answers with its id, but their widget driver implements both for Element Call web. This speaks
// the widget API to that driver in-process, with no web view. Room state and to-device messaging have
// left it for the SDK (26.09.26). Delete this package once the bindings gain the entry points listed in
// `docs/FEEDBACK.md`, "Widget-driver stopgap", and have `SdkElementCallMatrixRoom` call them directly.

package io.element.android.call.matrix.temporary.widget

import io.element.android.call.api.matrix.ElementCallDelayedEventAction
import io.element.android.call.api.matrix.ElementCallMatrixException
import io.element.android.call.api.matrix.ElementCallStickyEvent
import io.element.android.call.api.rtc.id.EventId
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.matrix.ElementCallTemporaryApi
import io.element.android.call.matrix.util.runCatchingExceptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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
 * capabilities provider and confirms with `notify_capabilities`. Requests sent before that are
 * silently dropped, and the driver keeps at most 15 unanswered requests of its own, so everything it
 * sends is answered inline - including anything it pushes that the bridge no longer reads.
 *
 * [parentScope] is where the driver's loops run. The bridge makes its own child of it
 * so [stop] can cancel the loops without cancelling the caller; a child of the client session scope
 * rather than of the call's, because the leave itself still goes through the bridge.
 */
@ElementCallTemporaryApi
internal class WidgetMatrixBridge(
    val roomId: RoomId,
    private val widgetId: String,
    private val driver: WidgetDriver,
    parentScope: CoroutineScope,
    private val requestTimeout: Duration = 30.seconds,
) {
    private enum class Phase { IDLE, NEGOTIATING, READY, STOPPED }

    private val json = Json { ignoreUnknownKeys = true }

    private val driverScope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext.job))

    private val phase = MutableStateFlow(Phase.IDLE)

    /** Guards every phase transition, so a response and a teardown never both settle the same request. */
    private val lifecycleMutex = Mutex()

    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()

    // Lifecycle

    suspend fun start(): Result<Unit> {
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

    suspend fun stop() {
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

    /** A message-like event: `send_event` with neither state key nor delay, answered by the event id. */
    suspend fun sendRoomEvent(eventType: String, contentJson: String): Result<EventId> {
        val content = parseObject(contentJson)
            ?: return Result.failure(ElementCallMatrixException.InvalidResponse("content is not a JSON object"))
        val data = buildJsonObject {
            put("type", eventType)
            put("content", content)
        }
        return request(SEND_EVENT, data).mapCatching { response ->
            val eventId = response.string("event_id")
                ?: throw ElementCallMatrixException.InvalidResponse("no event_id in the send_event response")
            EventId(eventId)
        }
    }

    suspend fun sendDelayedEvent(eventType: String, stateKey: String?, contentJson: String, delayMs: ULong): Result<String> {
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

    suspend fun updateDelayedEvent(delayId: String, action: ElementCallDelayedEventAction): Result<Unit> {
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

    // The signature mirrors the port's; the widget API has no sticky events to send them through.
    @Suppress("UnusedParameter")
    suspend fun sendStickyEvent(eventType: String, contentJson: String, durationMs: ULong): Result<String> {
        return Result.failure(ElementCallMatrixException.NotSupported("MSC4354 sticky event $eventType"))
    }

    fun stickyEvents(): Flow<List<ElementCallStickyEvent>> {
        Timber.w("WidgetBridge: sticky events are not available through the widget driver, no sticky snapshot will be fed for $roomId")
        return emptyFlow()
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
            // State and to-device come from the SDK now; anything else the machine pushes is only acked.
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

    /** Fails everything in flight. Call under [lifecycleMutex]. */
    private fun tearDown() {
        phase.value = Phase.STOPPED
        for (requestId in pending.keys.toList()) {
            pending.remove(requestId)?.completeExceptionally(ElementCallMatrixException.NotRunning(roomId))
        }
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
        const val UPDATE_DELAYED_EVENT = "org.matrix.msc4157.update_delayed_event"
        const val SUPPORTED_API_VERSIONS = "supported_api_versions"

        // toWidget actions
        const val CAPABILITIES = "capabilities"
        const val NOTIFY_CAPABILITIES = "notify_capabilities"
    }
}
