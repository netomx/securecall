package com.cim.securecall

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.cim.securecall.data.parseFriendId
import com.cim.securecall.ui.AppRoot
import com.cim.securecall.ui.theme.KalloqTheme

// AppCompatActivity (not ComponentActivity) so the per-app language from Settings applies.
class MainActivity : AppCompatActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleInvite(intent)
        setContent {
            KalloqTheme {
                AppRoot(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInvite(intent)
    }

    private fun handleInvite(intent: Intent?) {
        val link = intent?.data?.toString() ?: return
        parseFriendId(link)?.let(vm::openInvite)
    }
}
