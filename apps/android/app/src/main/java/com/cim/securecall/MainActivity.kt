package com.cim.securecall

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.cim.securecall.data.parseFriendId
import com.cim.securecall.ui.AppRoot
import com.cim.securecall.ui.theme.KalloqTheme
import kotlinx.coroutines.launch

// AppCompatActivity (not ComponentActivity) so the per-app language from Settings applies.
class MainActivity : AppCompatActivity() {
    private val controller get() = (application as KalloqApp).controller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        // While a call is ringing or ongoing: show over the lock screen, wake the screen, keep it on.
        lifecycleScope.launch {
            controller.call.collect { call -> showForCall(call != null && call.phase != CallPhase.Ended) }
        }
        setContent {
            KalloqTheme {
                AppRoot(controller)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.appVisible = true
    }

    override fun onStop() {
        controller.appVisible = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_ANSWER) {
            // "Answer" on the incoming-call notification. Without the microphone permission the call
            // screen's own answer button asks for it.
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                controller.acceptCall()
            }
            return
        }
        val link = intent?.data?.toString() ?: return
        parseFriendId(link)?.let(controller::openInvite)
    }

    companion object {
        const val ACTION_ANSWER = "com.cim.securecall.ANSWER"
    }

    @Suppress("DEPRECATION")
    private fun showForCall(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(on)
            setTurnScreenOn(on)
        } else {
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (on) window.addFlags(flags) else window.clearFlags(flags)
        }
        // FLAG_SECURE: the call screen stays black in screenshots and screen recordings.
        val keep = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE
        if (on) window.addFlags(keep) else window.clearFlags(keep)
    }
}
