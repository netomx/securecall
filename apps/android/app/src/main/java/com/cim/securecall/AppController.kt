package com.cim.securecall

import android.app.Application
import android.os.SystemClock
import androidx.annotation.StringRes
import com.cim.securecall.call.AudioRoute
import com.cim.securecall.call.AudioRouter
import com.cim.securecall.call.CallEngine
import com.cim.securecall.call.CallNotifications
import com.cim.securecall.call.CallService
import com.cim.securecall.call.CallTones
import com.cim.securecall.data.Identity
import com.cim.securecall.data.LocalStore
import com.cim.securecall.data.SERVER_URL
import com.cim.securecall.data.b64url
import com.cim.securecall.data.parseFriendId
import com.cim.securecall.net.ConnState
import com.cim.securecall.net.ServerClient
import com.cim.securecall.net.ServerError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import java.util.UUID

sealed interface Phase {
    data object Loading : Phase
    data object LoggedOut : Phase
    data class ShowRecovery(val code: String) : Phase
    data object LoggedIn : Phase
}

sealed interface Notice {
    data class Error(val code: String) : Notice
    data class Info(@StringRes val text: Int) : Notice
}

data class Friend(
    val id: String,
    val alias: String?,
    val ik: String?,
    val fingerprint: String?,
    val keyChanged: Boolean,
) {
    val displayName: String get() = alias ?: id.take(8)
}

data class UiState(
    val phase: Phase = Phase.Loading,
    val conn: ConnState = ConnState.Disconnected,
    val myId: String? = null,
    val username: String? = null,
    val friends: List<Friend> = emptyList(),
    val incoming: List<String> = emptyList(),
    val outgoing: List<String> = emptyList(),
    val blocked: List<String> = emptyList(),
    val busy: Boolean = false,
    val notice: Notice? = null,
    val pendingInvite: String? = null,
)

enum class CallPhase { Calling, Incoming, Connecting, Active, Ended }

data class CallUi(
    val callId: String,
    val peerId: String,
    val outgoing: Boolean,
    val phase: CallPhase,
    val connectedAt: Long? = null,  // SystemClock.elapsedRealtime() when audio connected
    val muted: Boolean = false,
    val route: AudioRoute = AudioRoute.Earpiece,
    val bluetoothAvailable: Boolean = false,
    val endReason: String? = null,
)

class AppController(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = LocalStore(app)
    private val server = ServerClient(SERVER_URL)
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private var sessionJob: Job? = null
    private var authenticated = false  // the current socket is bound to our account

    // Call
    private val engine = CallEngine(app, scope)
    private val audio = AudioRouter(app)
    private val tones = CallTones(app)
    private val _call = MutableStateFlow<CallUi?>(null)
    val call: StateFlow<CallUi?> = _call
    private var pendingOffer: String? = null

    init {
        scope.launch {
            server.state.collect { s ->
                if (s == ConnState.Disconnected) {
                    authenticated = false
                    _call.value?.let { if (it.phase != CallPhase.Ended) finishCall("disconnected") }
                }
                _ui.update { it.copy(conn = s) }
            }
        }
        scope.launch { server.events.collect(::onEvent) }
        scope.launch {
            combine(audio.route, audio.bluetoothAvailable) { r, bt -> r to bt }.collect { (r, bt) ->
                _call.update { it?.copy(route = r, bluetoothAvailable = bt) }
            }
        }

        if (store.token != null) {
            _ui.update { it.copy(phase = Phase.LoggedIn, myId = store.userId, username = store.username) }
            keepSession()
        } else {
            _ui.update { it.copy(phase = Phase.LoggedOut) }
        }
    }

    // ---------------------------------------------------------------- session

    /** Keeps the socket connected and authenticated while logged in, reconnecting with backoff. */
    private fun keepSession() {
        sessionJob?.cancel()
        sessionJob = scope.launch {
            var backoff = 1_000L
            while (isActive) {
                val token = store.token ?: return@launch
                try {
                    server.connect().await()
                    if (!authenticated) {
                        server.call("auth") { put("token", token) }
                        authenticated = true
                    }
                    afterAuth()
                    backoff = 1_000L
                    server.state.first { it == ConnState.Disconnected }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ServerError) {
                    if (e.code == "invalid_token") {
                        signedOut(R.string.notice_session_expired)
                        return@launch
                    }
                } catch (_: Exception) {
                    // network error: retry below
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private suspend fun afterAuth() {
        val seed = store.identitySeed ?: Identity.newSeed().also { store.identitySeed = it }
        server.call("set_key") { put("ik", b64url(Identity.publicKey(seed))) }
        refreshContacts()
    }

    /** The current socket is now logged in; keepSession() uploads our key and loads contacts. */
    private fun signedIn(reply: JsonObject, username: String) {
        val name = username.trim().lowercase()
        if (store.username != null && store.username != name) store.resetAccount()
        store.token = reply.str("token")
        store.userId = reply.str("user_id")
        store.username = name
        authenticated = true
        _ui.update { it.copy(myId = store.userId, username = name) }
        keepSession()
    }

    private fun signedOut(@StringRes reason: Int?) {
        sessionJob?.cancel()
        sessionJob = null
        _call.value?.let { finishCall(null) }
        store.clearSession()
        server.disconnect()
        _ui.update {
            UiState(phase = Phase.LoggedOut, notice = reason?.let(Notice::Info), pendingInvite = it.pendingInvite)
        }
    }

    // ---------------------------------------------------------------- events

    private fun onEvent(ev: JsonObject) {
        when (ev.str("ev")) {
            "session_revoked" -> signedOut(R.string.notice_revoked)
            "call_incoming" -> onIncomingCall(ev)
            "call_answered" -> onCallAnswered(ev)
            "call_ice" -> onRemoteIce(ev)
            "call_ended" -> if (ev.str("call_id") == _call.value?.callId) finishCall(ev.str("reason"))
            else -> scope.launch {
                when (ev.str("ev")) {
                    "friend_request" -> notice(Notice.Info(R.string.notice_friend_request))
                    "friend_accepted" -> notice(Notice.Info(R.string.notice_friend_accepted))
                    "key_changed" -> notice(Notice.Info(R.string.notice_key_changed))
                    "friend_id_changed" -> {
                        val old = ev.str("old_id")
                        val new = ev.str("new_id")
                        if (old != null && new != null) store.renameContact(old, new)
                    }
                }
                runCatching { refreshContacts() }
            }
        }
    }

    private suspend fun refreshContacts() {
        val r = server.call("contacts")
        val pins = store.pins()
        val friends = r["friends"]!!.jsonArray.map { el ->
            val o = el.jsonObject
            val id = o.str("user_id")!!
            val ik = o.str("ik")
            var pinned = pins[id]
            if (pinned == null && ik != null) {
                store.setPin(id, ik)  // trust on first use
                pinned = ik
            }
            Friend(id, null, ik, ik?.let(Identity::fingerprint), keyChanged = ik != null && pinned != ik)
        }
        _ui.update {
            it.copy(
                friends = withAliases(friends),
                incoming = r.strings("incoming"),
                outgoing = r.strings("outgoing"),
                blocked = r.strings("blocked"),
            )
        }
    }

    private fun withAliases(friends: List<Friend>): List<Friend> {
        val aliases = store.aliases()
        return friends.map { it.copy(alias = aliases[it.id]) }.sortedBy { it.displayName.lowercase() }
    }

    fun displayName(id: String): String = _ui.value.friends.find { it.id == id }?.displayName ?: id.take(8)

    // ---------------------------------------------------------------- account & friends

    private fun act(block: suspend () -> Unit) {
        scope.launch {
            _ui.update { it.copy(busy = true) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ServerError) {
                notice(Notice.Error(e.code))
            } catch (_: Exception) {
                notice(Notice.Error("network"))
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    fun register(username: String, password: String) = act {
        val r = server.call("register") {
            put("username", username.trim())
            put("password", password)
        }
        store.resetAccount()
        signedIn(r, username)
        _ui.update { it.copy(phase = Phase.ShowRecovery(r.str("recovery_code")!!)) }
    }

    fun login(username: String, password: String) = act {
        val r = server.call("login") {
            put("username", username.trim())
            put("password", password)
        }
        signedIn(r, username)
        _ui.update { it.copy(phase = Phase.LoggedIn) }
    }

    fun recover(username: String, code: String, newPassword: String) = act {
        val r = server.call("recover") {
            put("username", username.trim())
            put("recovery_code", code)
            put("new_password", newPassword)
        }
        signedIn(r, username)
        _ui.update { it.copy(phase = Phase.ShowRecovery(r.str("recovery_code")!!)) }
    }

    fun recoverySaved() = _ui.update { it.copy(phase = Phase.LoggedIn) }

    fun logout() = act {
        runCatching { server.call("logout") }
        signedOut(null)
    }

    fun deleteAccount(password: String) = act {
        server.call("delete_account") { put("password", password) }
        store.resetAccount()
        signedOut(null)
    }

    fun changePassword(current: String, new: String) = act {
        server.call("change_password") {
            put("password", current)
            put("new_password", new)
        }
        notice(Notice.Info(R.string.notice_password_changed))
    }

    fun regenRecovery(password: String) = act {
        val r = server.call("regen_recovery") { put("password", password) }
        _ui.update { it.copy(phase = Phase.ShowRecovery(r.str("recovery_code")!!)) }
    }

    fun regenId() = act {
        val r = server.call("regen_id")
        store.userId = r.str("user_id")
        _ui.update { it.copy(myId = store.userId) }
    }

    fun addFriend(input: String) {
        val id = parseFriendId(input)
        when {
            id == null -> notice(Notice.Error("invalid_id"))
            id == _ui.value.myId -> notice(Notice.Error("self"))
            else -> act {
                val r = server.call("friend_request") { put("user_id", id) }
                refreshContacts()
                val friends = r.str("status") == "friends"
                notice(Notice.Info(if (friends) R.string.notice_now_friends else R.string.notice_request_sent))
            }
        }
    }

    fun respond(id: String, accept: Boolean) = act {
        server.call("friend_respond") {
            put("user_id", id)
            put("accept", accept)
        }
        refreshContacts()
    }

    fun removeFriend(id: String) = act {
        server.call("friend_remove") { put("user_id", id) }
        refreshContacts()
    }

    fun block(id: String) = act {
        server.call("block") { put("user_id", id) }
        refreshContacts()
    }

    fun unblock(id: String) = act {
        server.call("unblock") { put("user_id", id) }
        refreshContacts()
    }

    fun setAlias(id: String, alias: String) {
        store.setAlias(id, alias)
        _ui.update { it.copy(friends = withAliases(it.friends)) }
    }

    /** The user confirmed the friend's new key: pin it. */
    fun trustKey(id: String) {
        val friend = _ui.value.friends.find { it.id == id } ?: return
        friend.ik?.let { store.setPin(id, it) }
        _ui.update { s -> s.copy(friends = s.friends.map { if (it.id == id) it.copy(keyChanged = false) else it }) }
    }

    fun openInvite(id: String) = _ui.update { it.copy(pendingInvite = id) }

    fun closeInvite() = _ui.update { it.copy(pendingInvite = null) }

    fun notice(n: Notice) = _ui.update { it.copy(notice = n) }

    fun dismissNotice() = _ui.update { it.copy(notice = null) }

    // ---------------------------------------------------------------- calls

    private val engineListener = object : CallEngine.Listener {
        override fun onIceCandidate(candidate: IceCandidate) {
            val c = _call.value ?: return
            scope.launch {
                runCatching {
                    server.call("call_ice") {
                        put("call_id", c.callId)
                        put("candidate", buildJsonObject {
                            put("candidate", candidate.sdp)
                            put("sdpMid", candidate.sdpMid)
                            put("sdpMLineIndex", candidate.sdpMLineIndex)
                        })
                    }
                }
            }
        }

        override fun onConnected() {
            tones.stop()
            _call.update { c -> c?.copy(phase = CallPhase.Active, connectedAt = c.connectedAt ?: SystemClock.elapsedRealtime()) }
        }

        override fun onFailed() {
            val c = _call.value ?: return
            scope.launch { runCatching { server.call("call_end") { put("call_id", c.callId) } } }
            finishCall("failed")
        }
    }

    private suspend fun iceServers(): List<PeerConnection.IceServer> {
        val r = server.call("turn")
        val urls = r.strings("urls")
        return listOf(
            PeerConnection.IceServer.builder(urls)
                .setUsername(r.str("username"))
                .setPassword(r.str("credential"))
                .createIceServer()
        )
    }

    /** Requires RECORD_AUDIO (the UI asks for it first). */
    fun startCall(friendId: String) {
        if (_call.value != null) return
        val callId = UUID.randomUUID().toString()
        _call.value = CallUi(callId, friendId, outgoing = true, phase = CallPhase.Calling)
        CallService.start(app, displayName(friendId))
        audio.start()
        tones.ringback()
        scope.launch {
            try {
                engine.open(iceServers(), engineListener)
                val offer = engine.createOffer()
                server.call("call") {
                    put("user_id", friendId)
                    put("call_id", callId)
                    put("sdp", offer)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ServerError) {
                finishCall(e.code)
            } catch (_: Exception) {
                finishCall("network")
            }
        }
    }

    private fun onIncomingCall(ev: JsonObject) {
        val callId = ev.str("call_id") ?: return
        val from = ev.str("user_id") ?: return
        if (_call.value != null) {
            // Already in a call (the server normally answers "busy" before we get here).
            scope.launch { runCatching { server.call("call_reject") { put("call_id", callId) } } }
            return
        }
        pendingOffer = ev.str("sdp")
        _call.value = CallUi(callId, from, outgoing = false, phase = CallPhase.Incoming)
        tones.ring()
        if (!appVisible) CallNotifications.showIncoming(app, displayName(from))
    }

    /** Set by MainActivity. The incoming-call notification is only needed when the call screen is not visible. */
    var appVisible = false
        set(value) {
            field = value
            val c = _call.value
            when {
                value -> CallNotifications.cancelIncoming(app)
                c?.phase == CallPhase.Incoming -> CallNotifications.showIncoming(app, displayName(c.peerId))
            }
        }

    /** Requires RECORD_AUDIO (the UI asks for it first). */
    fun acceptCall() {
        val c = _call.value?.takeIf { it.phase == CallPhase.Incoming } ?: return
        val offer = pendingOffer ?: return
        pendingOffer = null
        tones.stop()
        CallNotifications.cancelIncoming(app)
        _call.value = c.copy(phase = CallPhase.Connecting)
        CallService.start(app, displayName(c.peerId))
        audio.start()
        scope.launch {
            try {
                engine.open(iceServers(), engineListener)
                val answer = engine.acceptOffer(offer)
                server.call("call_answer") {
                    put("call_id", c.callId)
                    put("sdp", answer)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ServerError) {
                finishCall(e.code)
            } catch (_: Exception) {
                runCatching { server.call("call_end") { put("call_id", c.callId) } }
                finishCall("failed")
            }
        }
    }

    fun rejectCall() {
        val c = _call.value?.takeIf { it.phase == CallPhase.Incoming } ?: return
        scope.launch { runCatching { server.call("call_reject") { put("call_id", c.callId) } } }
        finishCall(null)
    }

    fun hangUp() {
        val c = _call.value ?: return
        if (c.phase == CallPhase.Ended) return
        if (c.phase == CallPhase.Incoming) return rejectCall()
        scope.launch { runCatching { server.call("call_end") { put("call_id", c.callId) } } }
        finishCall("hangup")
    }

    private fun onCallAnswered(ev: JsonObject) {
        val c = _call.value ?: return
        if (ev.str("call_id") != c.callId || !c.outgoing) return
        val sdp = ev.str("sdp") ?: return
        tones.stop()
        _call.value = c.copy(phase = CallPhase.Connecting)
        scope.launch {
            runCatching { engine.setAnswer(sdp) }.onFailure { engineListener.onFailed() }
        }
    }

    private fun onRemoteIce(ev: JsonObject) {
        if (ev.str("call_id") != _call.value?.callId) return
        val o = runCatching { ev["candidate"]!!.jsonObject }.getOrNull() ?: return
        val sdp = o.str("candidate") ?: return
        val index = o["sdpMLineIndex"]?.jsonPrimitive?.intOrNull ?: 0
        engine.addRemoteCandidate(IceCandidate(o.str("sdpMid"), index, sdp))
    }

    fun toggleMute() {
        val c = _call.value ?: return
        engine.setMuted(!c.muted)
        _call.value = c.copy(muted = !c.muted)
    }

    fun toggleSpeaker() {
        val c = _call.value ?: return
        audio.setRoute(if (c.route == AudioRoute.Speaker) AudioRoute.Earpiece else AudioRoute.Speaker)
    }

    fun toggleBluetooth() {
        val c = _call.value ?: return
        audio.setRoute(if (c.route == AudioRoute.Bluetooth) AudioRoute.Earpiece else AudioRoute.Bluetooth)
    }

    /** Tears down media and shows the reason for a moment before closing the call screen. */
    private fun finishCall(reason: String?) {
        val c = _call.value ?: return
        if (c.phase == CallPhase.Ended) return
        pendingOffer = null
        tones.stop()
        engine.close()
        audio.stop()
        CallService.stop(app)
        CallNotifications.cancelIncoming(app)
        if (reason == null) {
            _call.value = null
            return
        }
        _call.value = c.copy(phase = CallPhase.Ended, endReason = reason, muted = false, route = AudioRoute.Earpiece)
        scope.launch {
            delay(1_800)
            _call.update { if (it?.callId == c.callId && it.phase == CallPhase.Ended) null else it }
        }
    }
}

private fun JsonObject.str(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

private fun JsonObject.strings(key: String): List<String> =
    this[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
