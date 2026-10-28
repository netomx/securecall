package com.cim.securecall

import android.app.Application
import com.cim.securecall.call.CallNotifications

class KalloqApp : Application() {
    /** App-wide state: the connection and an ongoing call must outlive the activity. */
    lateinit var controller: AppController
        private set

    override fun onCreate() {
        super.onCreate()
        CallNotifications.createChannels(this)
        controller = AppController(this)
    }
}
