package com.cim.securecall.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.CircularProgressIndicator
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
import com.cim.securecall.AppViewModel
import com.cim.securecall.Phase
import com.cim.securecall.R
import com.cim.securecall.UiState

sealed interface Screen {
    data object Contacts : Screen
    data object AddFriend : Screen
    data object Settings : Screen
    data class Contact(val id: String) : Screen
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
            }
        }
    },
    restore = { saved ->
        saved.map {
            when {
                it == "add" -> Screen.AddFriend
                it == "settings" -> Screen.Settings
                it.startsWith("contact:") -> Screen.Contact(it.removePrefix("contact:"))
                else -> Screen.Contacts
            }
        }
    },
)

@Composable
fun AppRoot(vm: AppViewModel) {
    val ui by vm.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val notice = ui.notice
    val noticeMessage = notice?.let { noticeText(it) }
    LaunchedEffect(notice) {
        if (noticeMessage != null) {
            snackbar.showSnackbar(noticeMessage)
            vm.dismissNotice()
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (val phase = ui.phase) {
            Phase.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            Phase.LoggedOut -> AuthFlow(vm, ui)
            is Phase.ShowRecovery -> RecoveryCodeScreen(phase.code, onDone = vm::recoverySaved)
            Phase.LoggedIn -> MainFlow(vm, ui)
        }
        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding(),
        )
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
private fun MainFlow(vm: AppViewModel, ui: UiState) {
    var stack by rememberSaveable(stateSaver = StackSaver) { mutableStateOf(listOf<Screen>(Screen.Contacts)) }
    val push: (Screen) -> Unit = { stack = stack + it }
    val pop: () -> Unit = { stack = stack.dropLast(1) }
    BackHandler(enabled = stack.size > 1, onBack = pop)

    when (val top = stack.last()) {
        Screen.Contacts -> ContactsScreen(
            ui = ui,
            onAdd = { push(Screen.AddFriend) },
            onSettings = { push(Screen.Settings) },
            onOpen = { push(Screen.Contact(it)) },
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
                    onAlias = { vm.setAlias(friend.id, it) },
                    onTrustKey = { vm.trustKey(friend.id) },
                    onRemove = { vm.removeFriend(friend.id) },
                    onBlock = { vm.block(friend.id) },
                )
            }
        }
    }
}

@Composable
private fun AuthFlow(vm: AppViewModel, ui: UiState) {
    var recovering by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = recovering) { recovering = false }
    if (recovering) {
        RecoverScreen(
            busy = ui.busy,
            onBack = { recovering = false },
            onRecover = vm::recover,
            onMismatch = { vm.notice(com.cim.securecall.Notice.Error("mismatch")) },
        )
    } else {
        AuthScreen(
            busy = ui.busy,
            onLogin = vm::login,
            onRegister = vm::register,
            onForgot = { recovering = true },
            onMismatch = { vm.notice(com.cim.securecall.Notice.Error("mismatch")) },
        )
    }
}
