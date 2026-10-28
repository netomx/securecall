package com.cim.securecall

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cim.securecall.data.Identity
import com.cim.securecall.data.LocalStore
import com.cim.securecall.data.SERVER_URL
import com.cim.securecall.data.b64url
import com.cim.securecall.data.parseFriendId
import com.cim.securecall.net.ConnState
import com.cim.securecall.net.ServerClient
import com.cim.securecall.net.ServerError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = LocalStore(app)
    private val server = ServerClient(SERVER_URL)
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private var sessionJob: Job? = null
    private var authenticated = false  // the current socket is bound to our account

    init {
        viewModelScope.launch {
            server.state.collect { s ->
                if (s == ConnState.Disconnected) authenticated = false
                _ui.update { it.copy(conn = s) }
            }
        }
        viewModelScope.launch { server.events.collect(::onEvent) }

        if (store.token != null) {
            _ui.update { it.copy(phase = Phase.LoggedIn, myId = store.userId, username = store.username) }
            keepSession()
        } else {
            _ui.update { it.copy(phase = Phase.LoggedOut) }
        }
    }

    override fun onCleared() {
        server.disconnect()
    }

    // ---------------------------------------------------------------- session

    /** Keeps the socket connected and authenticated while logged in, reconnecting with backoff. */
    private fun keepSession() {
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch {
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
        store.clearSession()
        server.disconnect()
        _ui.update {
            UiState(phase = Phase.LoggedOut, notice = reason?.let(Notice::Info), pendingInvite = it.pendingInvite)
        }
    }

    // ---------------------------------------------------------------- events

    private fun onEvent(ev: JsonObject) {
        val name = ev.str("ev") ?: return
        if (name == "session_revoked") {
            signedOut(R.string.notice_revoked)
            return
        }
        viewModelScope.launch {
            when (name) {
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

    // ---------------------------------------------------------------- actions

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
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
}

private fun JsonObject.str(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

private fun JsonObject.strings(key: String): List<String> =
    this[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
