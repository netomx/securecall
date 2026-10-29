@file:OptIn(ExperimentalMaterial3Api::class)

package com.cim.securecall.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cim.securecall.R

@Composable
private fun AuthColumn(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

@Composable
private fun UsernameField(value: String, onValueChange: (String) -> Unit, supportingText: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onValueChange(v.filterNot(Char::isWhitespace)) },
        label = { Text(stringResource(R.string.username)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
        supportingText = supportingText?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BusyButton(text: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text(text)
    }
}

@Composable
fun AuthScreen(
    busy: Boolean,
    onLogin: (String, String) -> Unit,
    onRegister: (String, String) -> Unit,
    onForgot: () -> Unit,
    onMismatch: () -> Unit,
) {
    var registering by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }

    AuthColumn {
        Spacer(Modifier.height(24.dp))
        Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(96.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(R.string.tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !registering,
                onClick = { registering = false },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text(stringResource(R.string.login)) }
            SegmentedButton(
                selected = registering,
                onClick = { registering = true },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text(stringResource(R.string.register)) }
        }
        Spacer(Modifier.height(16.dp))

        val canSubmit = username.isNotBlank() && password.isNotEmpty() && !busy
        val submit = {
            when {
                !canSubmit -> Unit
                !registering -> onLogin(username, password)
                password != confirm -> onMismatch()
                else -> onRegister(username, password)
            }
        }

        UsernameField(username, { username = it }, if (registering) stringResource(R.string.username_hint) else null)
        PasswordField(
            password,
            { password = it },
            stringResource(R.string.password),
            supportingText = if (registering) stringResource(R.string.password_hint) else null,
            onDone = if (registering) null else submit,
        )
        if (registering) PasswordField(confirm, { confirm = it }, stringResource(R.string.confirm_password), onDone = submit)
        Spacer(Modifier.height(16.dp))

        BusyButton(
            text = stringResource(if (registering) R.string.register else R.string.login),
            busy = busy,
            enabled = canSubmit,
            onClick = submit,
        )
        if (!registering) {
            TextButton(onClick = onForgot) { Text(stringResource(R.string.forgot_password)) }
        }
    }
}

@Composable
fun RecoverScreen(
    busy: Boolean,
    onBack: () -> Unit,
    onRecover: (String, String, String) -> Unit,
    onMismatch: () -> Unit,
) {
    var username by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }

    AuthColumn {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(stringResource(R.string.recover_title), style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(16.dp))
        UsernameField(username, { username = it })
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.uppercase() },
            label = { Text(stringResource(R.string.recovery_code)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        PasswordField(password, { password = it }, stringResource(R.string.new_password), supportingText = stringResource(R.string.password_hint))
        val canSubmit = username.isNotBlank() && code.isNotBlank() && password.isNotEmpty() && !busy
        val submit = {
            when {
                !canSubmit -> Unit
                password != confirm -> onMismatch()
                else -> onRecover(username, code, password)
            }
        }
        PasswordField(confirm, { confirm = it }, stringResource(R.string.confirm_password), onDone = submit)
        Spacer(Modifier.height(16.dp))
        BusyButton(stringResource(R.string.recover_action), busy, canSubmit, onClick = submit)
    }
}

@Composable
fun RecoveryCodeScreen(code: String, onDone: () -> Unit) {
    val context = LocalContext.current
    var saved by rememberSaveable { mutableStateOf(false) }

    AuthColumn {
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.recovery_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.recovery_body), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                code.split("-").chunked(4).forEach { row ->
                    Text(
                        row.joinToString("  "),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { copyToClipboard(context, code, sensitive = true) }) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.copy))
        }
        Spacer(Modifier.height(24.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = saved, role = Role.Checkbox, onValueChange = { saved = it }),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Checkbox(checked = saved, onCheckedChange = null)
            Spacer(Modifier.size(12.dp))
            Text(stringResource(R.string.recovery_saved_check))
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onDone, enabled = saved, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(stringResource(R.string.continue_action))
        }
    }
}
