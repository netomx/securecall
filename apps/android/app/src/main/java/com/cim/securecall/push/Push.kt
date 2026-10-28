package com.cim.securecall.push

import android.os.Handler
import android.os.Looper
import com.cim.securecall.KalloqApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Firebase Cloud Messaging. The server sends a data-only message that says nothing but "someone is
 * calling, connect": the call itself (caller, encrypted offer) arrives over our own connection.
 */
object Push {
    /** This phone's FCM token; null without Google Play services or in a build without google-services.json. */
    suspend fun token(): String? = try {
        val messaging = FirebaseMessaging.getInstance()
        suspendCancellableCoroutine { cont ->
            messaging.token.addOnCompleteListener { task -> cont.resume(if (task.isSuccessful) task.result else null) }
        }
    } catch (_: IllegalStateException) {
        null  // Firebase not configured in this build
    }
}

class KalloqMessagingService : FirebaseMessagingService() {
    private val main = Handler(Looper.getMainLooper())
    private val controller get() = (application as KalloqApp).controller

    override fun onNewToken(token: String) {
        main.post { controller.onPushToken(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["t"] == "call") main.post { controller.onPushWake() }
    }
}
