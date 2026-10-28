package com.cim.securecall

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import com.cim.securecall.call.CallNotifications

class KalloqApp : Application() {
    /** App-wide state: the connection and an ongoing call must outlive the activity. */
    lateinit var controller: AppController
        private set

    override fun onCreate() {
        super.onCreate()
        CallNotifications.createChannels(this)
        // No app may record what Kalloq plays (calls, ringback), not even system screen recorders.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(AudioManager::class.java).setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
        }
        controller = AppController(this)
    }
}
