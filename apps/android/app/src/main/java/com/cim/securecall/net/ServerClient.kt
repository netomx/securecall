package com.cim.securecall.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Error code returned by the server: {"ok": false, "error": code}. See docs/PROTOCOLO.md. */
class ServerError(val code: String) : Exception(code)

enum class ConnState { Disconnected, Connecting, Connected }

/** JSON-over-WebSocket client for sc-server: request/response by id, plus a stream of events. */
class ServerClient(private val url: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()

    @Volatile private var socket: WebSocket? = null
    private var ready = CompletableDeferred<Unit>()

    private val _state = MutableStateFlow(ConnState.Disconnected)
    val state: StateFlow<ConnState> = _state

    private val _events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)
    val events: SharedFlow<JsonObject> = _events

    /** Opens the socket if needed; completes when the server's hello arrives. */
    @Synchronized
    fun connect(): Deferred<Unit> {
        if (_state.value != ConnState.Disconnected) return ready
        _state.value = ConnState.Connecting
        ready = CompletableDeferred()
        socket = http.newWebSocket(Request.Builder().url(url).build(), Listener())
        return ready
    }

    @Synchronized
    fun disconnect() {
        val s = socket ?: return
        s.close(1000, null)
        closed(s)
    }

    suspend fun call(op: String, params: JsonObjectBuilder.() -> Unit = {}): JsonObject {
        withTimeout(CONNECT_TIMEOUT_MS) { connect().await() }
        val id = nextId.getAndIncrement()
        val reply = CompletableDeferred<JsonObject>()
        pending[id] = reply
        try {
            val msg = buildJsonObject {
                put("id", id)
                put("op", op)
                params()
            }
            if (socket?.send(msg.toString()) != true) throw IOException("not connected")
            val r = withTimeout(CALL_TIMEOUT_MS) { reply.await() }
            if (r["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                throw ServerError(r["error"]?.jsonPrimitive?.contentOrNull ?: "internal")
            }
            return r
        } finally {
            pending.remove(id)
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            val ev = obj["ev"]?.jsonPrimitive?.contentOrNull
            when {
                ev == "hello" -> {
                    _state.value = ConnState.Connected
                    ready.complete(Unit)
                }
                ev != null -> _events.tryEmit(obj)
                else -> obj["id"]?.jsonPrimitive?.longOrNull?.let { pending[it]?.complete(obj) }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = closed(webSocket)
    }

    @Synchronized
    private fun closed(ws: WebSocket) {
        if (ws !== socket) return
        socket = null
        _state.value = ConnState.Disconnected
        val error = IOException("disconnected")
        ready.completeExceptionally(error)
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val CALL_TIMEOUT_MS = 20_000L
    }
}
