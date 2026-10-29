package com.cim.securecall.call

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The call as Android's telephony system sees it (self-managed Telecom call, through core-telecom: a
 * ConnectionService up to Android 13, the call API of Android 14+). Android then knows we are in a call:
 * a SIM call puts ours on hold instead of mixing both, the car, a watch or a headset button can answer
 * and hang up, and the system owns audio mode, focus and routing ([AudioRouter] follows it).
 *
 * Best effort: when the system refuses the call, [Listener.onUnavailable] runs and the app handles the
 * call on its own, as before.
 */
class SystemCall(context: Context, private val scope: CoroutineScope, private val audio: AudioRouter) {
    /** System requests, delivered on the main thread. Throwing makes the system drop the request. */
    interface Listener {
        fun onSystemAnswer()
        fun onSystemDisconnect()
        fun onHold(held: Boolean)
        fun onUnavailable()
    }

    private val manager = CallsManager(context)

    @SuppressLint("MissingPermission")  // MANAGE_OWN_CALLS is a normal permission, granted at install
    private val registered = runCatching { manager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE) }
        .onFailure { Log.w(TAG, "Telecom not available", it) }
        .isSuccess

    private var job: Job? = null
    // Requests made before the system finished adding the call wait here, in order.
    private var actions: Channel<suspend CallControlScope.() -> Unit>? = null

    /** Registers a new call with the system. Returns at once; [listener] hears from the system. */
    @SuppressLint("MissingPermission")
    fun start(peerName: String, peerId: String, incoming: Boolean, listener: Listener) {
        end(DisconnectCause.LOCAL)
        if (!registered) return listener.onUnavailable()
        val queue = Channel<suspend CallControlScope.() -> Unit>(Channel.UNLIMITED).also { actions = it }
        val attributes = CallAttributesCompat(
            displayName = peerName,
            address = Uri.fromParts("kalloq", peerId, null),
            direction = if (incoming) CallAttributesCompat.DIRECTION_INCOMING else CallAttributesCompat.DIRECTION_OUTGOING,
            callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
        )
        job = scope.launch {
            var added = false
            try {
                manager.addCall(
                    attributes,
                    onAnswer = { onMain { listener.onSystemAnswer() } },
                    onDisconnect = { onMain { listener.onSystemDisconnect() } },
                    onSetActive = { onMain { listener.onHold(false) } },
                    onSetInactive = { onMain { listener.onHold(true) } },
                ) {
                    added = true
                    audio.followSystem(this)
                    launch { for (action in queue) runCatching { action() }.onFailure { Log.w(TAG, "call action", it) } }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "addCall failed", e)
                if (!added && actions === queue) listener.onUnavailable()
            } finally {
                if (actions === queue) actions = null
                audio.stopFollowingSystem()
            }
        }
    }

    /** We answered (our screen or notification). */
    fun answer() = send { answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL) }

    /** The call is up (outgoing: the other side answered). */
    fun active() = send { setActive() }

    /** Take the call off hold; the system holds whatever other call is active. [done] gets false if it refused. */
    fun resume(done: (Boolean) -> Unit) {
        if (actions == null) return done(true)  // the system does not know this call: nothing to ask
        send {
            val ok = setActive() is CallControlResult.Success
            withContext(Dispatchers.Main.immediate) { done(ok) }
        }
    }

    fun end(cause: Int) {
        val queue = actions ?: return
        val current = job
        send { disconnect(DisconnectCause(cause)) }
        queue.close()
        actions = null
        // Never leave a call behind in the system if it does not confirm the disconnect.
        scope.launch {
            if (withTimeoutOrNull(6_000) { current?.join() } == null) current?.cancel()
        }
    }

    private fun send(action: suspend CallControlScope.() -> Unit) {
        actions?.trySend(action)
    }

    private suspend fun onMain(block: () -> Unit) = withContext(Dispatchers.Main.immediate) { block() }

    private companion object {
        const val TAG = "SystemCall"
    }
}
