package com.cim.securecall

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.StringRes
import com.cim.securecall.call.AudioRoute
import com.cim.securecall.call.AudioRouter
import com.cim.securecall.call.CallEngine
import com.cim.securecall.call.CallNotifications
import com.cim.securecall.call.CallService
import com.cim.securecall.call.CallTones
import com.cim.securecall.crypto.CallCrypto
import com.cim.securecall.crypto.NoiseHandshake
import com.cim.securecall.crypto.Transport
import com.cim.securecall.crypto.Verification
import com.cim.securecall.data.Identity
import com.cim.securecall.data.LocalStore
import com.cim.securecall.data.SERVER_URL
import com.cim.securecall.data.VerifyRecord
import com.cim.securecall.data.b64url
import com.cim.securecall.data.b64urlDecode
import com.cim.securecall.data.parseFriendId
import com.cim.securecall.net.ConnState
import com.cim.securecall.net.ServerClient
import com.cim.securecall.net.ServerError
import com.cim.securecall.push.Push
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
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
    val pinned: String? = null,        // the identity key we trust for this contact
    val verified: Boolean = false,     // QR verification done on our side
    val verifyCode: String? = null,    // shown on both phones after scanning; must match
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
    val pushReady: Boolean = false,  // the server can wake this phone for calls
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
    val verified: Boolean = false,     // the contact was verified in person (QR)
    val code: String? = null,          // comparison code from the handshake, same on both phones
)

class AppController(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = LocalStore(app)
    private val server = ServerClient(SERVER_URL)
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private var sessionJob: Job? = null
    private val authState = MutableStateFlow(false)
    private var authenticated: Boolean  // the current socket is bound to our account
        get() = authState.value
        set(value) { authState.value = value }

    // Call
    private val engine = CallEngine(app, scope)
    private val audio = AudioRouter(app)
    private val tones = CallTones(app)
    private val _call = MutableStateFlow<CallUi?>(null)
    val call: StateFlow<CallUi?> = _call
    private var pendingOffer: String? = null   // decrypted SDP offer of a ringing call
    private var handshake: NoiseHandshake? = null
    private var transport: Transport? = null
    private var iceReady = false                    // our candidates can be sent (handshake done, answer out)
    private val iceOut = ArrayList<String>()        // our candidates waiting for iceReady
    private var ringSeenAt = 0L                     // last time the server (re)delivered the ringing call
    private var ringTimeout: Job? = null

    init {
        scope.launch {
            server.state.collect { s ->
                if (s == ConnState.Disconnected) {
                    authenticated = false
                    // A ringing call survives on the server and is delivered again once we reconnect,
                    // so it keeps ringing here; afterAuth() notices if it ended meanwhile.
                    _call.value?.let { if (it.phase != CallPhase.Incoming && it.phase != CallPhase.Ended) finishCall("disconnected") }
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

        // A network coming back is the best moment to reconnect: don't wait out the backoff.
        app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch { reconnectNow() }
                }
            }
        )

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
            var backoff = 500L
            while (isActive) {
                val token = store.token ?: return@launch
                try {
                    server.connect().await()
                    val authAt = SystemClock.elapsedRealtime()
                    if (!authenticated) {
                        server.call("auth") { put("token", token) }
                        authenticated = true
                    }
                    afterAuth()
                    // The server re-sends a call that is still ringing right after auth: no re-send means it ended
                    // while we were offline (most likely the caller gave up).
                    _call.value?.let { if (it.phase == CallPhase.Incoming && ringSeenAt < authAt) finishCall("hangup") }
                    backoff = 500L
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
                backoff = (backoff * 2).coerceAtMost(10_000L)
            }
        }
    }

    /** Reconnects at once if we are offline (the app came to the front, the network came back...). */
    private fun reconnectNow() {
        if (store.token != null && server.state.value == ConnState.Disconnected) keepSession()
    }

    /** Waits a few seconds for the connection to be logged in, kicking a reconnect if needed. */
    private suspend fun awaitAuthenticated(): Boolean {
        if (authenticated) return true
        reconnectNow()
        return withTimeoutOrNull(10_000) { authState.first { it } } != null
    }

    private suspend fun afterAuth() {
        val seed = store.identitySeed ?: Identity.newSeed().also { store.identitySeed = it }
        server.call("set_key") { put("ik", b64url(Identity.publicKey(seed))) }
        refreshContacts()
        Push.token()?.let { sendPushToken(it) }
    }

    // ---------------------------------------------------------------- push

    private val pushWakeLock = app.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kalloq:push")
        .apply { setReferenceCounted(false) }

    private suspend fun sendPushToken(token: String) {
        runCatching { server.call("set_push") { put("token", token) } }
            .onSuccess { _ui.update { it.copy(pushReady = true) } }
    }

    /** FCM rotated this phone's token. */
    fun onPushToken(token: String) {
        if (authenticated) scope.launch { sendPushToken(token) }
    }

    /** A push says someone is calling: make sure our connection is up so the server can deliver the call. */
    fun onPushWake() {
        if (store.token == null) return
        pushWakeLock.acquire(30_000)
        scope.launch {
            when (server.state.value) {
                ConnState.Connecting -> Unit  // just woken up: already on its way
                ConnState.Disconnected -> reconnectNow()  // skip the reconnect backoff
                ConnState.Connected -> {
                    // The socket may have died silently while the phone slept.
                    val alive = runCatching { withTimeout(4_000) { server.call("ping") } }.isSuccess
                    if (!alive) {
                        server.disconnect()
                        keepSession()
                    }
                }
            }
        }
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
            Friend(id, null, ik, ik?.let(Identity::fingerprint), keyChanged = ik != null && pinned != ik, pinned = pinned)
        }
        _ui.update {
            it.copy(
                friends = decorate(friends),
                incoming = r.strings("incoming"),
                outgoing = r.strings("outgoing"),
                blocked = r.strings("blocked"),
            )
        }
    }

    /** Adds what only this phone knows: aliases and QR verification. */
    private fun decorate(friends: List<Friend>): List<Friend> {
        val aliases = store.aliases()
        val verifications = store.verifications()
        return friends.map { f ->
            val psk = verifications[f.id]?.takeIf { it.ik == f.pinned }?.psk?.let(::b64urlDecode)
            f.copy(alias = aliases[f.id], verified = psk != null, verifyCode = psk?.let(Verification::code))
        }.sortedBy { it.displayName.lowercase() }
    }

    private fun redecorate() = _ui.update { it.copy(friends = decorate(it.friends)) }

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
        redecorate()
    }

    /** The user confirmed the friend's new key: pin it. */
    fun trustKey(id: String) {
        val friend = _ui.value.friends.find { it.id == id } ?: return
        val ik = friend.ik ?: return
        store.setPin(id, ik)
        store.updateVerifications { remove(id) }  // the QR verification was for the old key
        _ui.update { s -> s.copy(friends = decorate(s.friends.map { if (it.id == id) it.copy(keyChanged = false, pinned = ik) else it })) }
    }

    // ---------------------------------------------------------------- in-person verification

    /** Our QR for verifying [friendId]. Its random value is kept until the verification is reset. */
    fun verifyQrText(friendId: String): String? {
        val pinned = store.pins()[friendId] ?: return null
        val seed = store.identitySeed ?: return null
        val myId = store.userId ?: return null
        val rec = store.verifications()[friendId]?.takeIf { it.ik == pinned }
            ?: VerifyRecord(pinned, b64url(Verification.newRandom())).also { store.updateVerifications { put(friendId, it) } }
        return Verification.encode(myId, Identity.publicKey(seed), b64urlDecode(rec.rSelf)!!)
    }

    /** The user scanned [friendId]'s verification QR. */
    fun scanVerify(friendId: String, text: String) {
        val friend = _ui.value.friends.find { it.id == friendId } ?: return
        val code = Verification.decode(text)
        val seed = store.identitySeed
        val pinned = friend.pinned?.let(::b64urlDecode)
        when {
            code == null || seed == null || pinned == null -> notice(Notice.Error("verify_invalid"))
            code.userId != friendId -> notice(Notice.Error("verify_other"))
            friend.keyChanged || !code.ik.contentEquals(pinned) -> notice(Notice.Error("verify_key"))
            else -> {
                verifyQrText(friendId)  // makes sure our random value exists
                store.updateVerifications {
                    val rec = getValue(friendId)
                    val psk = Verification.psk(seed, Identity.publicKey(seed), b64urlDecode(rec.rSelf)!!, code.ik, code.r)
                    put(friendId, rec.copy(rOther = b64url(code.r), psk = b64url(psk)))
                }
                redecorate()
                notice(Notice.Info(R.string.verify_done))
            }
        }
    }

    /** Back to the automatic (TOFU) key; a new QR value is generated next time. */
    fun resetVerification(friendId: String) {
        store.updateVerifications { remove(friendId) }
        redecorate()
    }

    fun openInvite(id: String) = _ui.update { it.copy(pendingInvite = id) }

    fun closeInvite() = _ui.update { it.copy(pendingInvite = null) }

    fun notice(n: Notice) = _ui.update { it.copy(notice = n) }

    fun dismissNotice() = _ui.update { it.copy(notice = null) }

    // ---------------------------------------------------------------- calls

    private val engineListener = object : CallEngine.Listener {
        override fun onIceCandidate(candidate: IceCandidate) {
            val callId = _call.value?.callId ?: return
            val json = buildJsonObject {
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            }.toString()
            scope.launch {
                if (_call.value?.callId != callId) return@launch
                iceOut += json
                flushIce()
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

    private class E2E(val seed: ByteArray, val peerIk: ByteArray, val psk: ByteArray?)

    /** Keys for a call with [friendId]: the pinned identity key and the QR PSK if verified. Null if the key changed and was not confirmed. */
    private fun e2eFor(friendId: String): E2E? {
        val pinned = store.pins()[friendId] ?: return null
        if (_ui.value.friends.find { it.id == friendId }?.keyChanged == true) return null
        val seed = store.identitySeed ?: return null
        val psk = store.verifications()[friendId]?.takeIf { it.ik == pinned }?.psk?.let(::b64urlDecode)
        return E2E(seed, b64urlDecode(pinned) ?: return null, psk)
    }

    /** Sends our ICE candidates, encrypted, once the handshake allows it. */
    private fun flushIce() {
        val c = _call.value ?: return
        val t = transport?.takeIf { iceReady } ?: return
        for (cand in iceOut) {
            val msg = b64url(t.encrypt(cand.encodeToByteArray()))
            scope.launch { runCatching { server.call("call_ice") { put("call_id", c.callId); put("msg", msg) } } }
        }
        iceOut.clear()
    }

    /** Requires RECORD_AUDIO (the UI asks for it first). */
    fun startCall(friendId: String) {
        if (_call.value != null) return
        val e2e = e2eFor(friendId) ?: return notice(Notice.Error("key_changed"))
        val callId = UUID.randomUUID().toString()
        _call.value = CallUi(callId, friendId, outgoing = true, phase = CallPhase.Calling, verified = e2e.psk != null)
        CallService.start(app, displayName(friendId))
        audio.start()
        tones.ringback()
        scope.launch {
            try {
                // Calling right after opening the app: give the connection a moment instead of failing.
                if (!awaitAuthenticated()) return@launch finishCall("network")
                if (_call.value?.let { it.callId == callId && it.phase == CallPhase.Calling } != true) return@launch  // hung up while waiting
                engine.open(iceServers(), engineListener)
                val offer = engine.createOffer()
                val hs = CallCrypto.handshake(true, e2e.seed, e2e.peerIk, e2e.psk, callId)
                val payload = buildJsonObject {
                    put("sdp", offer)
                    put("psk", CallCrypto.pskId(e2e.psk))
                }.toString()
                handshake = hs
                server.call("call") {
                    put("user_id", friendId)
                    put("call_id", callId)
                    put("msg", b64url(hs.writeOffer(payload.encodeToByteArray())))
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
        if (_call.value?.let { it.callId == callId && it.phase == CallPhase.Incoming } == true) {
            ringSeenAt = SystemClock.elapsedRealtime()  // the same call again, after we reconnected
            return
        }
        if (_call.value != null) {
            // Already in a call (the server normally answers "busy" before we get here).
            scope.launch { runCatching { server.call("call_reject") { put("call_id", callId) } } }
            return
        }
        val reject = { error: String ->
            scope.launch { runCatching { server.call("call_reject") { put("call_id", callId) } } }
            notice(Notice.Error(error))
        }
        // Only a call made with the identity key we pinned for this friend decrypts.
        val e2e = e2eFor(from) ?: return reject("key_changed")
        val hs = CallCrypto.handshake(false, e2e.seed, e2e.peerIk, e2e.psk, callId)
        val offer = runCatching {
            Json.parseToJsonElement(hs.readOffer(b64urlDecode(ev.str("msg")!!)!!).decodeToString()).jsonObject
        }.getOrNull() ?: return reject("e2e_failed")
        if (offer.str("psk") != CallCrypto.pskId(e2e.psk)) return reject("verify_mismatch")
        pendingOffer = offer.str("sdp") ?: return reject("e2e_failed")
        handshake = hs
        _call.value = CallUi(callId, from, outgoing = false, phase = CallPhase.Incoming, verified = e2e.psk != null)
        ringSeenAt = SystemClock.elapsedRealtime()
        // Keep the CPU awake while ringing so the "call ended" event is not missed in a sleeping phone,
        // and give up on our own if it never arrives (the server rings for 45 s).
        pushWakeLock.acquire(RING_LIMIT_MS)
        ringTimeout?.cancel()
        ringTimeout = scope.launch {
            delay(RING_LIMIT_MS)
            if (_call.value?.callId == callId && _call.value?.phase == CallPhase.Incoming) {
                runCatching { server.call("call_reject") { put("call_id", callId) } }
                finishCall("timeout")
            }
        }
        tones.ring()
        CallService.ring(app, displayName(from))
    }

    /** Set by MainActivity. The incoming-call notification only pops up when the call screen is not visible. */
    var appVisible = false
        set(value) {
            field = value
            if (value) reconnectNow()
            val c = _call.value
            if (c?.phase == CallPhase.Incoming) CallNotifications.showIncoming(app, displayName(c.peerId), headsUp = !value)
        }

    /** Requires RECORD_AUDIO (the UI asks for it first). */
    fun acceptCall() {
        val c = _call.value?.takeIf { it.phase == CallPhase.Incoming } ?: return
        val offer = pendingOffer ?: return
        pendingOffer = null
        ringTimeout?.cancel()
        tones.stop()
        CallNotifications.cancelIncoming(app)
        _call.value = c.copy(phase = CallPhase.Connecting)
        CallService.start(app, displayName(c.peerId))
        audio.start()
        scope.launch {
            try {
                engine.open(iceServers(), engineListener)
                val answer = engine.acceptOffer(offer)
                val hs = handshake ?: error("no handshake")
                val (msg, t) = hs.writeAnswer(buildJsonObject { put("sdp", answer) }.toString().encodeToByteArray())
                transport = t
                _call.update { if (it?.callId == c.callId) it.copy(code = CallCrypto.callCode(hs.handshakeHash)) else it }
                server.call("call_answer") {
                    put("call_id", c.callId)
                    put("msg", b64url(msg))
                }
                iceReady = true
                flushIce()
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
        val hs = handshake ?: return
        // Fails if the answer was not made by the pinned peer or the QR PSKs differ.
        val opened = runCatching {
            val (payload, t) = hs.readAnswer(b64urlDecode(ev.str("msg")!!)!!)
            Json.parseToJsonElement(payload.decodeToString()).jsonObject.str("sdp")!! to t
        }.getOrNull()
        if (opened == null) {
            scope.launch { runCatching { server.call("call_end") { put("call_id", c.callId) } } }
            finishCall("e2e_failed")
            return
        }
        val (sdp, t) = opened
        tones.stop()
        transport = t
        iceReady = true
        _call.value = c.copy(phase = CallPhase.Connecting, code = CallCrypto.callCode(hs.handshakeHash))
        flushIce()
        scope.launch {
            runCatching { engine.setAnswer(sdp) }.onFailure { engineListener.onFailed() }
        }
    }

    private fun onRemoteIce(ev: JsonObject) {
        if (ev.str("call_id") != _call.value?.callId) return
        val t = transport ?: return
        val o = runCatching { Json.parseToJsonElement(t.decrypt(b64urlDecode(ev.str("msg")!!)!!).decodeToString()).jsonObject }
            .getOrNull() ?: return
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
        handshake = null
        transport = null
        iceReady = false
        iceOut.clear()
        ringTimeout?.cancel()
        if (pushWakeLock.isHeld) pushWakeLock.release()
        tones.stop()
        engine.close()
        audio.stop()
        CallService.stop(app)
        CallNotifications.cancelIncoming(app)
        if (!c.outgoing && c.phase == CallPhase.Incoming && reason in setOf("hangup", "timeout", "disconnected")) {
            CallNotifications.missed(app, displayName(c.peerId))
        }
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

private const val RING_LIMIT_MS = 60_000L

private fun JsonObject.str(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

private fun JsonObject.strings(key: String): List<String> =
    this[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
