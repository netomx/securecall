package com.cim.securecall.call

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.cim.securecall.KalloqApp
import com.cim.securecall.MainActivity
import com.cim.securecall.R

/**
 * Keeps the process alive while a call rings (the app may have been woken by a push, with no
 * activity) and keeps the microphone alive during a call (screen off, app in background).
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = (application as KalloqApp).controller
        when (intent?.action) {
            ACTION_HANG_UP -> {
                controller.hangUp()
                return START_NOT_STICKY
            }
            ACTION_DECLINE -> {
                controller.rejectCall()
                return START_NOT_STICKY
            }
        }
        val name = intent?.getStringExtra(EXTRA_PEER) ?: ""
        val ringing = intent?.getBooleanExtra(EXTRA_RINGING, false) == true
        var type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL else 0
        if (!ringing && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            if (ringing) {
                ServiceCompat.startForeground(this, CallNotifications.INCOMING_ID, CallNotifications.incoming(this, name, !controller.appVisible), type)
            } else {
                ServiceCompat.startForeground(this, CallNotifications.ONGOING_ID, CallNotifications.ongoing(this, name), type)
                CallNotifications.cancelIncoming(this)
            }
        } catch (e: Exception) {
            // Not allowed from the background right now: the plain notification still lets the user answer.
            Log.w("CallService", "startForeground failed", e)
            if (ringing) CallNotifications.showIncoming(this, name, true)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val ACTION_HANG_UP = "com.cim.securecall.HANG_UP"
        private const val ACTION_DECLINE = "com.cim.securecall.DECLINE"
        private const val EXTRA_PEER = "peer"
        private const val EXTRA_RINGING = "ringing"

        /** Incoming call ringing: phoneCall foreground service showing the incoming-call notification. */
        fun ring(context: Context, peerName: String) = launch(context, peerName, ringing = true)

        /** Call answered or placed: adds the microphone type. */
        fun start(context: Context, peerName: String) = launch(context, peerName, ringing = false)

        private fun launch(context: Context, peerName: String, ringing: Boolean) {
            val intent = Intent(context, CallService::class.java).putExtra(EXTRA_PEER, peerName).putExtra(EXTRA_RINGING, ringing)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w("CallService", "startForegroundService failed", e)
                if (ringing) CallNotifications.showIncoming(context, peerName, true)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }

        fun hangUpIntent(context: Context): PendingIntent = PendingIntent.getService(
            context, 1, Intent(context, CallService::class.java).setAction(ACTION_HANG_UP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun declineIntent(context: Context): PendingIntent = PendingIntent.getService(
            context, 2, Intent(context, CallService::class.java).setAction(ACTION_DECLINE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

object CallNotifications {
    const val ONGOING_ID = 10
    const val INCOMING_ID = 11
    private const val MISSED_ID = 12
    private const val CHANNEL_INCOMING = "calls_incoming"
    private const val CHANNEL_INCOMING_QUIET = "calls_incoming_quiet"
    private const val CHANNEL_ONGOING = "calls_ongoing"
    private const val CHANNEL_MISSED = "calls_missed"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_INCOMING, context.getString(R.string.notif_channel_incoming), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)  // the app plays the ringtone itself
                enableVibration(false)
            }
        )
        // Same notification while the call screen is already visible: no heads-up on top of it.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_INCOMING_QUIET, context.getString(R.string.notif_channel_incoming_quiet), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, context.getString(R.string.notif_channel_ongoing), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MISSED, context.getString(R.string.notif_channel_missed), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    /**
     * Random per ring, carried by the notification's Answer intent. MainActivity is exported (invite links), so
     * without it any app could send ACTION_ANSWER and pick up the call.
     */
    @Volatile var answerToken: String? = null

    private fun openApp(context: Context, action: String? = null): PendingIntent = PendingIntent.getActivity(
        context, if (action == null) 0 else 3,
        Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (action != null) putExtra(MainActivity.EXTRA_ANSWER_TOKEN, answerToken) },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun ongoing(context: Context, peerName: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_ongoing_text, peerName))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(openApp(context))
            .addAction(0, context.getString(R.string.call_hangup), CallService.hangUpIntent(context))
            .build()

    /** Answer / decline notification; full screen when the phone is locked. [headsUp] false while the call screen is visible. */
    fun incoming(context: Context, peerName: String, headsUp: Boolean): Notification {
        val caller = Person.Builder().setName(peerName).setImportant(true).build()
        return NotificationCompat.Builder(context, if (headsUp) CHANNEL_INCOMING else CHANNEL_INCOMING_QUIET)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(peerName)
            .setContentText(context.getString(R.string.notif_incoming_text, peerName))
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, CallService.declineIntent(context), openApp(context, MainActivity.ACTION_ANSWER)))
            .setPriority(if (headsUp) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(openApp(context))
            .apply { if (headsUp) setFullScreenIntent(openApp(context), true) }
            .build()
    }

    private fun canNotify(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Posts or updates the incoming-call notification (also the one owned by the ringing service). */
    fun showIncoming(context: Context, peerName: String, headsUp: Boolean) {
        if (!canNotify(context)) return
        NotificationManagerCompat.from(context).notify(INCOMING_ID, incoming(context, peerName, headsUp))
    }

    fun cancelIncoming(context: Context) {
        NotificationManagerCompat.from(context).cancel(INCOMING_ID)
    }

    fun missed(context: Context, peerName: String) {
        if (!canNotify(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_MISSED)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.notif_missed_title))
            .setContentText(peerName)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .build()
        NotificationManagerCompat.from(context).notify(MISSED_ID, n)
    }
}
