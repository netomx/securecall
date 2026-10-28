@file:OptIn(ExperimentalMaterial3Api::class)

package com.cim.securecall.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.cim.securecall.AppViewModel
import com.cim.securecall.Friend
import com.cim.securecall.R
import com.cim.securecall.UiState
import com.cim.securecall.data.INVITE_BASE
import com.cim.securecall.data.SERVER_URL
import com.cim.securecall.net.ConnState
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
private fun BackTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        },
    )
}

@Composable
private fun Avatar(name: String, warning: Boolean = false) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.take(2).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun ConnectionDot(state: ConnState) {
    val (color, label) = when (state) {
        ConnState.Connected -> Color(0xFF22C55E) to R.string.status_connected
        ConnState.Connecting -> Color(0xFFF59E0B) to R.string.status_connecting
        ConnState.Disconnected -> MaterialTheme.colorScheme.error to R.string.status_offline
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.size(6.dp))
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ------------------------------------------------------------------ contacts

@Composable
fun ContactsScreen(
    ui: UiState,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onOpen: (String) -> Unit,
    onRespond: (String, Boolean) -> Unit,
    onCancelRequest: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name))
                        ConnectionDot(ui.conn)
                    }
                },
                actions = {
                    IconButton(onClick = onAdd) { Icon(Icons.Default.PersonAdd, stringResource(R.string.add_friend)) }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
                },
            )
        },
    ) { pad ->
        val empty = ui.friends.isEmpty() && ui.incoming.isEmpty() && ui.outgoing.isEmpty()
        if (empty) {
            Column(
                Modifier.fillMaxSize().padding(pad).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.empty_contacts), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onAdd) {
                    Icon(Icons.Default.PersonAdd, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.add_friend))
                }
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 80.dp)) {
            if (ui.incoming.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.section_requests)) }
                items(ui.incoming, key = { "in-$it" }) { id ->
                    ListItem(
                        leadingContent = { Avatar(id) },
                        headlineContent = { Text(id.take(8), fontFamily = FontFamily.Monospace) },
                        supportingContent = { Text(id, style = MaterialTheme.typography.bodySmall, maxLines = 1) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { onRespond(id, false) }) { Icon(Icons.Default.Close, stringResource(R.string.reject)) }
                                IconButton(onClick = { onRespond(id, true) }) {
                                    Icon(Icons.Default.Check, stringResource(R.string.accept), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        },
                    )
                }
            }
            if (ui.friends.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.section_friends)) }
                items(ui.friends, key = { "f-${it.id}" }) { f ->
                    ListItem(
                        modifier = Modifier.clickable { onOpen(f.id) },
                        leadingContent = { Avatar(f.displayName, warning = f.keyChanged) },
                        headlineContent = { Text(f.displayName) },
                        supportingContent = {
                            when {
                                f.keyChanged -> Text(stringResource(R.string.key_changed_short), color = MaterialTheme.colorScheme.error)
                                f.fingerprint != null -> Text(f.fingerprint.take(19), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                else -> Text(stringResource(R.string.no_key), style = MaterialTheme.typography.bodySmall)
                            }
                        },
                    )
                }
            }
            if (ui.outgoing.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.section_pending)) }
                items(ui.outgoing, key = { "out-$it" }) { id ->
                    ListItem(
                        leadingContent = { Avatar(id) },
                        headlineContent = { Text(id.take(8), fontFamily = FontFamily.Monospace) },
                        supportingContent = { Text(id, style = MaterialTheme.typography.bodySmall, maxLines = 1) },
                        trailingContent = {
                            TextButton(onClick = { onCancelRequest(id) }) { Text(stringResource(R.string.cancel)) }
                        },
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ add friend

@Composable
fun AddFriendScreen(ui: UiState, onBack: () -> Unit, onAdd: (String) -> Unit) {
    val context = LocalContext.current
    val link = INVITE_BASE + (ui.myId ?: "")
    val qr = remember(link) { qrBitmap(link) }
    var input by rememberSaveable { mutableStateOf("") }
    val scanPrompt = stringResource(R.string.scan_prompt)
    val scanner = androidx.activity.compose.rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(onAdd)
    }

    Scaffold(topBar = { BackTopBar(stringResource(R.string.add_friend), onBack) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.my_code), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.my_code_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Image(
                qr,
                contentDescription = link,
                modifier = Modifier.widthIn(max = 260.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.height(8.dp))
            Text(ui.myId ?: "", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copyToClipboard(context, link) }) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.copy))
                }
                OutlinedButton(onClick = { shareText(context, context.getString(R.string.share_text, link)) }) {
                    Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.share))
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 20.dp))

            Button(
                onClick = {
                    scanner.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt(scanPrompt)
                            .setBeepEnabled(false)
                            .setOrientationLocked(false)
                    )
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Default.QrCodeScanner, null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.scan_qr))
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(stringResource(R.string.friend_id_or_link)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                onClick = {
                    onAdd(input)
                    input = ""
                },
                enabled = input.isNotBlank() && !ui.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.send_request)) }
        }
    }
}

// ------------------------------------------------------------------ contact

@Composable
fun ContactScreen(
    friend: Friend,
    onBack: () -> Unit,
    onAlias: (String) -> Unit,
    onTrustKey: () -> Unit,
    onRemove: () -> Unit,
    onBlock: () -> Unit,
) {
    var alias by rememberSaveable(friend.id) { mutableStateOf(friend.alias ?: "") }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }

    Scaffold(topBar = { BackTopBar(friend.displayName, onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (friend.keyChanged) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.key_changed_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.key_changed_body), color = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onTrustKey) { Text(stringResource(R.string.trust_key)) }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            OutlinedTextField(
                value = alias,
                onValueChange = { alias = it.take(40) },
                label = { Text(stringResource(R.string.alias)) },
                singleLine = true,
                trailingIcon = {
                    TextButton(onClick = { onAlias(alias) }, enabled = alias != (friend.alias ?: "")) { Text(stringResource(R.string.save)) }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Default.Call, null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.call))
            }
            Text(
                stringResource(R.string.calls_soon),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                textAlign = TextAlign.Center,
            )

            SectionTitle(stringResource(R.string.user_id), Modifier.padding(top = 16.dp))
            Text(friend.id, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 16.dp))

            SectionTitle(stringResource(R.string.fingerprint), Modifier.padding(top = 8.dp))
            Text(
                friend.fingerprint ?: stringResource(R.string.no_key),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                stringResource(R.string.fingerprint_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TextButton(onClick = { confirmRemove = true }) {
                Icon(Icons.Default.PersonRemove, null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.remove_friend))
            }
            TextButton(onClick = { confirmBlock = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Default.Block, null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.block))
            }
        }
    }

    if (confirmRemove) {
        ConfirmDialog(stringResource(R.string.remove_friend), stringResource(R.string.confirm_remove), stringResource(R.string.remove_friend), onRemove) { confirmRemove = false }
    }
    if (confirmBlock) {
        ConfirmDialog(stringResource(R.string.block), stringResource(R.string.confirm_block), stringResource(R.string.block), onBlock) { confirmBlock = false }
    }
}

// ------------------------------------------------------------------ settings

private enum class Dialog { RegenId, ChangePassword, Recovery, Delete }

@Composable
private fun SettingRow(icon: ImageVector, text: String, color: Color = Color.Unspecified, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, null, tint = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color) },
        headlineContent = { Text(text, color = color) },
    )
}

@Composable
fun SettingsScreen(vm: AppViewModel, ui: UiState, onBack: () -> Unit) {
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    val currentLang = remember { AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(',').substringBefore('-') }
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?" }

    Scaffold(topBar = { BackTopBar(stringResource(R.string.settings), onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            SectionTitle(stringResource(R.string.account))
            ListItem(
                headlineContent = { Text(ui.username ?: "") },
                supportingContent = { Text(stringResource(R.string.username_hint), style = MaterialTheme.typography.bodySmall) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.my_id)) },
                supportingContent = { Text(ui.myId ?: "", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) },
                trailingContent = {
                    Row {
                        IconButton(onClick = { copyToClipboard(context, ui.myId ?: "") }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.copy)) }
                        IconButton(onClick = { dialog = Dialog.RegenId }) { Icon(Icons.Default.Refresh, stringResource(R.string.regen_id)) }
                    }
                },
            )

            HorizontalDivider()
            SectionTitle(stringResource(R.string.language))
            listOf("" to R.string.lang_system, "es" to R.string.lang_es, "en" to R.string.lang_en).forEach { (tag, label) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = currentLang == tag, role = Role.RadioButton) {
                            AppCompatDelegate.setApplicationLocales(
                                if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
                            )
                        }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = currentLang == tag, onClick = null)
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(label))
                }
            }

            HorizontalDivider(Modifier.padding(top = 8.dp))
            SectionTitle(stringResource(R.string.security))
            SettingRow(Icons.Default.Password, stringResource(R.string.change_password)) { dialog = Dialog.ChangePassword }
            SettingRow(Icons.Default.Key, stringResource(R.string.new_recovery_code)) { dialog = Dialog.Recovery }

            HorizontalDivider()
            SectionTitle(stringResource(R.string.blocked_users))
            if (ui.blocked.isEmpty()) {
                Text(stringResource(R.string.none), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            ui.blocked.forEach { id ->
                ListItem(
                    headlineContent = { Text(id, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) },
                    trailingContent = { TextButton(onClick = { vm.unblock(id) }) { Text(stringResource(R.string.unblock)) } },
                )
            }

            HorizontalDivider()
            SettingRow(Icons.AutoMirrored.Filled.Logout, stringResource(R.string.logout)) { vm.logout() }
            SettingRow(Icons.Default.DeleteForever, stringResource(R.string.delete_account), MaterialTheme.colorScheme.error) { dialog = Dialog.Delete }

            HorizontalDivider()
            SectionTitle(stringResource(R.string.about))
            Text(
                stringResource(R.string.version, version) + "\n" + stringResource(R.string.server, SERVER_URL),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            )
        }
    }

    when (dialog) {
        Dialog.RegenId -> ConfirmDialog(stringResource(R.string.regen_id), stringResource(R.string.regen_id_body), stringResource(R.string.confirm), vm::regenId) { dialog = null }
        Dialog.Recovery -> PasswordDialog(stringResource(R.string.new_recovery_code), null, vm::regenRecovery) { dialog = null }
        Dialog.Delete -> PasswordDialog(stringResource(R.string.delete_account), stringResource(R.string.delete_account_body), vm::deleteAccount) { dialog = null }
        Dialog.ChangePassword -> ChangePasswordDialog(onConfirm = vm::changePassword, onMismatch = { vm.notice(com.cim.securecall.Notice.Error("mismatch")) }) { dialog = null }
        null -> Unit
    }
}

@Composable
private fun ChangePasswordDialog(onConfirm: (String, String) -> Unit, onMismatch: () -> Unit, onDismiss: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.change_password)) },
        text = {
            Column {
                PasswordField(current, { current = it }, stringResource(R.string.current_password))
                PasswordField(new, { new = it }, stringResource(R.string.new_password), supportingText = stringResource(R.string.password_hint))
                PasswordField(confirm, { confirm = it }, stringResource(R.string.confirm_password))
            }
        },
        confirmButton = {
            TextButton(enabled = current.isNotEmpty() && new.isNotEmpty(), onClick = {
                if (new != confirm) onMismatch() else onConfirm(current, new)
                onDismiss()
            }) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
