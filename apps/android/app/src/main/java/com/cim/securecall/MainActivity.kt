package com.cim.securecall

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
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
        if (savedInstanceState == null) handleInvite(intent)
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
        handleInvite(intent)
    }

    private fun handleInvite(intent: Intent?) {
        val link = intent?.data?.toString() ?: return
        parseFriendId(link)?.let(controller::openInvite)
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
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
