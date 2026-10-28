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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.cim.securecall.KalloqApp
import com.cim.securecall.MainActivity
import com.cim.securecall.R

/** Keeps the process and microphone alive during a call (screen off, app in background). */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANG_UP) {
            (application as KalloqApp).controller.hangUp()
            return START_NOT_STICKY
        }
        val name = intent?.getStringExtra(EXTRA_PEER) ?: ""
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, CallNotifications.ONGOING_ID, CallNotifications.ongoing(this, name), type)
        return START_NOT_STICKY
    }

    companion object {
        private const val ACTION_HANG_UP = "com.cim.securecall.HANG_UP"
        private const val EXTRA_PEER = "peer"

        fun start(context: Context, peerName: String) {
            ContextCompat.startForegroundService(context, Intent(context, CallService::class.java).putExtra(EXTRA_PEER, peerName))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }

        fun hangUpIntent(context: Context): PendingIntent = PendingIntent.getService(
            context, 1, Intent(context, CallService::class.java).setAction(ACTION_HANG_UP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

object CallNotifications {
    const val ONGOING_ID = 10
    private const val INCOMING_ID = 11
    private const val CHANNEL_INCOMING = "calls_incoming"
    private const val CHANNEL_ONGOING = "calls_ongoing"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_INCOMING, context.getString(R.string.notif_channel_incoming), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)  // the app plays the ringtone itself
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, context.getString(R.string.notif_channel_ongoing), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
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

    /** Heads-up (or full screen when the phone is locked) while an incoming call rings. */
    fun showIncoming(context: Context, peerName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.call_incoming))
            .setContentText(context.getString(R.string.notif_incoming_text, peerName))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(openApp(context))
            .setFullScreenIntent(openApp(context), true)
            .build()
        NotificationManagerCompat.from(context).notify(INCOMING_ID, n)
    }

    fun cancelIncoming(context: Context) {
        NotificationManagerCompat.from(context).cancel(INCOMING_ID)
    }
}
