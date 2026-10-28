package com.cim.securecall.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cim.securecall.CallPhase
import com.cim.securecall.CallUi
import com.cim.securecall.R
import com.cim.securecall.call.AudioRoute
import kotlinx.coroutines.delay

private val CallBackground = Brush.verticalGradient(listOf(Color(0xFF0E5A73), Color(0xFF0B2E2B), Color(0xFF071614)))
private val Green = Color(0xFF16A34A)
private val Red = Color(0xFFDC2626)

@Composable
fun CallScreen(
    call: CallUi,
    peerName: String,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onHangUp: () -> Unit,
    onMute: () -> Unit,
    onSpeaker: () -> Unit,
    onBluetooth: () -> Unit,
) {
    BackHandler {}  // the call screen stays until the call ends

    Box(
        Modifier
            .fillMaxSize()
            .background(CallBackground)
            // swallow touches so nothing underneath reacts
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .safeDrawingPadding()
            .padding(24.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, null, tint = Color(0xFF5EEAD4), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.call_encrypted), color = Color(0xFF5EEAD4), style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(40.dp))
            Box(
                Modifier.size(120.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(peerName.take(2).uppercase(), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(20.dp))
            Text(peerName, color = Color.White, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(statusText(call), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.titleMedium)
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 24.dp)) {
            if (call.phase == CallPhase.Incoming) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundAction(Icons.Default.CallEnd, stringResource(R.string.call_decline), Red, onReject)
                    RoundAction(Icons.Default.Call, stringResource(R.string.call_answer), Green, onAccept)
                }
            } else {
                val live = call.phase != CallPhase.Ended
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ToggleAction(
                        icon = if (call.muted) Icons.Default.MicOff else Icons.Default.Mic,
                        label = stringResource(R.string.call_mute),
                        selected = call.muted,
                        enabled = live,
                        onClick = onMute,
                    )
                    ToggleAction(
                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                        label = stringResource(R.string.call_speaker),
                        selected = call.route == AudioRoute.Speaker,
                        enabled = live,
                        onClick = onSpeaker,
                    )
                    ToggleAction(
                        icon = if (call.route == AudioRoute.Bluetooth) Icons.Default.BluetoothAudio else Icons.Default.Bluetooth,
                        label = stringResource(R.string.call_bluetooth),
                        selected = call.route == AudioRoute.Bluetooth,
                        enabled = live && call.bluetoothAvailable,
                        onClick = onBluetooth,
                    )
                }
                Spacer(Modifier.height(40.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    RoundAction(Icons.Default.CallEnd, stringResource(R.string.call_hangup), Red, onHangUp, enabled = live)
                }
            }
        }
    }
}

@Composable
private fun statusText(call: CallUi): String {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(call.phase) {
        while (call.phase == CallPhase.Active) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    return when (call.phase) {
        CallPhase.Calling -> stringResource(R.string.call_calling)
        CallPhase.Incoming -> stringResource(R.string.call_incoming)
        CallPhase.Connecting -> stringResource(R.string.call_connecting)
        CallPhase.Active -> {
            val secs = ((now - (call.connectedAt ?: now)) / 1000).coerceAtLeast(0)
            "%d:%02d".format(secs / 60, secs % 60)
        }
        CallPhase.Ended -> stringResource(
            when (call.endReason) {
                "rejected" -> R.string.call_end_rejected
                "busy" -> R.string.call_end_busy
                "offline" -> R.string.call_end_offline
                "timeout" -> R.string.call_end_timeout
                "disconnected" -> R.string.call_end_disconnected
                "failed" -> R.string.call_end_failed
                "network" -> R.string.err_network
                "in_call" -> R.string.call_end_in_call
                else -> R.string.call_ended
            }
        )
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, color: Color, onClick: () -> Unit, enabled: Boolean = true) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(if (enabled) color else color.copy(alpha = 0.4f))
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ToggleAction(icon: ImageVector, label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.35f
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(if (selected) Color.White else Color.White.copy(alpha = 0.14f * alpha))
                .clickable(enabled = enabled, role = Role.Switch, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = if (selected) Color(0xFF0B2E2B) else Color.White.copy(alpha = alpha), modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White.copy(alpha = alpha), style = MaterialTheme.typography.labelMedium)
    }
}
