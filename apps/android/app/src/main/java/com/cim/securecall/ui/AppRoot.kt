package com.cim.securecall.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.cim.securecall.Notice
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.cim.securecall.AppController
import com.cim.securecall.Phase
import com.cim.securecall.R
import com.cim.securecall.UiState

sealed interface Screen {
    data object Contacts : Screen
    data object AddFriend : Screen
    data object Settings : Screen
    data class Contact(val id: String) : Screen
    data class Verify(val id: String) : Screen
}

// Saved as strings so the stack survives the activity being recreated (e.g. after a language change).
private val StackSaver = listSaver<List<Screen>, String>(
    save = { stack ->
        stack.map {
            when (it) {
                Screen.Contacts -> "contacts"
                Screen.AddFriend -> "add"
                Screen.Settings -> "settings"
                is Screen.Contact -> "contact:${it.id}"
                is Screen.Verify -> "verify:${it.id}"
            }
        }
    },
    restore = { saved ->
        saved.map {
            when {
                it == "add" -> Screen.AddFriend
                it == "settings" -> Screen.Settings
                it.startsWith("contact:") -> Screen.Contact(it.removePrefix("contact:"))
                it.startsWith("verify:") -> Screen.Verify(it.removePrefix("verify:"))
                else -> Screen.Contacts
            }
        }
    },
)

/** Runs `action` once RECORD_AUDIO is granted, asking for it first if needed. */
@Composable
private fun rememberWithMic(onDenied: () -> Unit): ((() -> Unit) -> Unit) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pending?.invoke() else onDenied()
        pending = null
    }
    return { action ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pending = action
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

@Composable
fun AppRoot(vm: AppController) {
    val ui by vm.ui.collectAsState()
    val call by vm.call.collectAsState()
    val withMic = rememberWithMic { vm.notice(Notice.Error("mic")) }
    val snackbar = remember { SnackbarHostState() }
    val notice = ui.notice
    val noticeMessage = notice?.let { noticeText(it) }
    LaunchedEffect(notice) {
        if (noticeMessage != null) {
            snackbar.showSnackbar(noticeMessage)
            vm.dismissNotice()
        }
    }

    // Surface sets the theme's background and content color, so plain Text follows dark mode
    // on screens that have no Scaffold (login, recovery code).
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            when (val phase = ui.phase) {
                Phase.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                Phase.LoggedOut -> AuthFlow(vm, ui)
                is Phase.ShowRecovery -> RecoveryCodeScreen(phase.code, onDone = vm::recoverySaved)
                Phase.LoggedIn -> {
                    MainFlow(vm, ui, onCall = { id -> withMic { vm.startCall(id) } })
                    call?.let { c ->
                        CallScreen(
                            call = c,
                            peerName = vm.displayName(c.peerId),
                            onAccept = { withMic(vm::acceptCall) },
                            onReject = vm::rejectCall,
                            onHangUp = vm::hangUp,
                            onMute = vm::toggleMute,
                            onSpeaker = vm::toggleSpeaker,
                            onBluetooth = vm::toggleBluetooth,
                        )
                    }
                }
            }
            SnackbarHost(
                snackbar,
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .imePadding(),
            )
        }
    }

    val invite = ui.pendingInvite
    if (invite != null && ui.phase == Phase.LoggedIn) {
        ConfirmDialog(
            title = stringResource(R.string.invite_title),
            body = stringResource(R.string.invite_body, invite),
            confirm = stringResource(R.string.send_request),
            onConfirm = { vm.addFriend(invite) },
            onDismiss = vm::closeInvite,
        )
    }
}

@Composable
private fun MainFlow(vm: AppController, ui: UiState, onCall: (String) -> Unit) {
    var stack by rememberSaveable(stateSaver = StackSaver) { mutableStateOf(listOf<Screen>(Screen.Contacts)) }
    val push: (Screen) -> Unit = { stack = stack + it }
    val pop: () -> Unit = { stack = stack.dropLast(1) }
    BackHandler(enabled = stack.size > 1, onBack = pop)

    // Android 13+: incoming calls are shown as a notification when the app is in the background.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        val context = LocalContext.current
        LaunchedEffect(Unit) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    when (val top = stack.last()) {
        Screen.Contacts -> ContactsScreen(
            ui = ui,
            onAdd = { push(Screen.AddFriend) },
            onSettings = { push(Screen.Settings) },
            onOpen = { push(Screen.Contact(it)) },
            onCall = onCall,
            onRespond = vm::respond,
            onCancelRequest = vm::removeFriend,
        )
        Screen.AddFriend -> AddFriendScreen(ui, onBack = pop, onAdd = vm::addFriend)
        Screen.Settings -> SettingsScreen(vm, ui, onBack = pop)
        is Screen.Contact -> {
            val friend = ui.friends.find { it.id == top.id }
            if (friend == null) {
                LaunchedEffect(top.id) { pop() }
            } else {
                ContactScreen(
                    friend = friend,
                    onBack = pop,
                    onCall = { onCall(friend.id) },
                    onAlias = { vm.setAlias(friend.id, it) },
                    onTrustKey = { vm.trustKey(friend.id) },
                    onVerify = { push(Screen.Verify(friend.id)) },
                    onRemove = { vm.removeFriend(friend.id) },
                    onBlock = { vm.block(friend.id) },
                )
            }
        }
        is Screen.Verify -> {
            val friend = ui.friends.find { it.id == top.id }
            if (friend == null || friend.keyChanged) {
                LaunchedEffect(top.id) { pop() }
            } else {
                VerifyScreen(vm, friend, onBack = pop)
            }
        }
    }
}

@Composable
private fun AuthFlow(vm: AppController, ui: UiState) {
    var recovering by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = recovering) { recovering = false }
    if (recovering) {
        RecoverScreen(
            busy = ui.busy,
            onBack = { recovering = false },
            onRecover = vm::recover,
            onMismatch = { vm.notice(Notice.Error("mismatch")) },
        )
    } else {
        AuthScreen(
            busy = ui.busy,
            onLogin = vm::login,
            onRegister = vm::register,
            onForgot = { recovering = true },
            onMismatch = { vm.notice(Notice.Error("mismatch")) },
        )
    }
}
