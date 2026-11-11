package com.cim.securecall.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cim.securecall.CallPhase
import com.cim.securecall.CallUi
import com.cim.securecall.R
import com.cim.securecall.call.AudioRoute
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

private val CallBackground = Brush.verticalGradient(listOf(Color(0xFF0E5A73), Color(0xFF0B2E2B), Color(0xFF071614)))
private val Green = Color(0xFF16A34A)
private val Red = Color(0xFFDC2626)

/** What the call screen needs to draw video. */
class CallVideo(val egl: EglBase.Context, val local: VideoTrack?, val remote: VideoTrack?, val frontCamera: Boolean)

@Composable
fun CallScreen(
    call: CallUi,
    peerName: String,
    video: CallVideo,
    onAccept: () -> Unit,
    onAcceptVideo: () -> Unit,
    onReject: () -> Unit,
    onHangUp: () -> Unit,
    onMute: () -> Unit,
    onCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onSpeaker: () -> Unit,
    onBluetooth: () -> Unit,
    onResume: () -> Unit,
) {
    BackHandler {}  // the call screen stays until the call ends

    val live = call.phase != CallPhase.Ended && call.phase != CallPhase.Incoming
    // The other side's picture fills the screen; ours fills it until the call is up.
    val remoteFull = live && call.peerVideo && call.peerCamera && !call.onHold && !call.peerOnHold && video.remote != null
    val localFull = live && !remoteFull && call.cameraOn && video.local != null && call.phase != CallPhase.Active
    val localSmall = live && call.cameraOn && video.local != null && !localFull
    val onVideo = remoteFull || localFull

    Box(
        Modifier
            .fillMaxSize()
            .background(CallBackground)
            // swallow touches so nothing underneath reacts
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        if (remoteFull) VideoView(video.remote, video.egl, mirror = false, Modifier.fillMaxSize())
        else if (localFull) VideoView(video.local, video.egl, mirror = video.frontCamera, Modifier.fillMaxSize())

        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
            CallContent(call, peerName, video, onVideo, localFull, localSmall, onAccept, onAcceptVideo, onReject, onHangUp,
                onMute, onCamera, onSwitchCamera, onSpeaker, onBluetooth, onResume)
        }
    }
}

@Composable
private fun BoxScope.CallContent(
    call: CallUi,
    peerName: String,
    video: CallVideo,
    onVideo: Boolean,
    localFull: Boolean,
    localSmall: Boolean,
    onAccept: () -> Unit,
    onAcceptVideo: () -> Unit,
    onReject: () -> Unit,
    onHangUp: () -> Unit,
    onMute: () -> Unit,
    onCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onSpeaker: () -> Unit,
    onBluetooth: () -> Unit,
    onResume: () -> Unit,
) {
    val live = call.phase != CallPhase.Ended && call.phase != CallPhase.Incoming
    if (localSmall) {
        Box(Modifier.align(Alignment.TopEnd).padding(top = 8.dp)) {
            VideoView(
                video.local, video.egl, mirror = video.frontCamera,
                Modifier.size(width = 104.dp, height = 156.dp).clip(RoundedCornerShape(12.dp)),
                overlay = true,
            )
            SmallAction(
                Icons.Default.Cameraswitch, stringResource(R.string.call_switch_camera), onSwitchCamera,
                Modifier.align(Alignment.BottomCenter).offset(y = 20.dp),
            )
        }
    } else if (localFull) {
        SmallAction(Icons.Default.Cameraswitch, stringResource(R.string.call_switch_camera), onSwitchCamera, Modifier.align(Alignment.TopEnd))
    }
    // On video the header moves to the side, out of the way of the picture and of our own camera.
    Column(
        Modifier.fillMaxWidth().padding(top = 32.dp, end = if (localSmall) 112.dp else 0.dp),
        horizontalAlignment = if (onVideo || localSmall) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, null, tint = Color(0xFF5EEAD4), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(if (call.verified) R.string.call_encrypted_verified else R.string.call_encrypted),
                color = Color(0xFF5EEAD4),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (!onVideo) {
            Spacer(Modifier.height(40.dp))
            Box(
                Modifier.size(120.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(peerName.take(2).uppercase(), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(20.dp))
        } else {
            Spacer(Modifier.height(16.dp))
        }
        // Over a picture the text needs a shadow to stay readable.
        val shadow = TextStyle(shadow = if (onVideo) Shadow(Color.Black.copy(alpha = 0.7f), blurRadius = 8f) else null)
        Text(
            peerName, color = Color.White, textAlign = TextAlign.Center,
            style = (if (onVideo) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium).merge(shadow),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            statusText(call),
            color = if (call.reconnecting || call.onHold || call.peerOnHold) Color(0xFFFBBF24) else Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.titleMedium.merge(shadow),
        )
        if (call.phase == CallPhase.Active && call.onHold) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onResume) { Text(stringResource(R.string.call_resume), color = Color.White) }
        }
        if (call.phase == CallPhase.Active && !call.reconnecting && !call.onHold && !call.peerOnHold && call.rttMs != null) {
            Spacer(Modifier.height(12.dp))
            QualityChip(call.rttMs)
        }
        if (live && call.peerProto != null && !call.peerVideo) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.call_no_video_old_app, peerName),
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.25f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        if (call.code != null && call.phase != CallPhase.Ended && !onVideo) {
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.call_code, call.code),
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 24.dp)) {
        if (call.phase == CallPhase.Incoming) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RoundAction(Icons.Default.CallEnd, stringResource(R.string.call_decline), Red, onReject)
                if (call.video) {
                    RoundAction(Icons.Default.Call, stringResource(R.string.call_answer_voice), Green, onAccept)
                    RoundAction(Icons.Default.Videocam, stringResource(R.string.call_answer_video), Green, onAcceptVideo)
                } else {
                    RoundAction(Icons.Default.Call, stringResource(R.string.call_answer), Green, onAccept)
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ToggleAction(
                    icon = if (call.muted) Icons.Default.MicOff else Icons.Default.Mic,
                    label = stringResource(R.string.call_mute),
                    selected = call.muted,
                    enabled = live,
                    onClick = onMute,
                )
                ToggleAction(
                    icon = if (call.cameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                    label = stringResource(R.string.call_camera),
                    selected = call.cameraOn,
                    enabled = live && call.peerVideo && !call.onHold,
                    onClick = onCamera,
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
                RoundAction(Icons.Default.CallEnd, stringResource(R.string.call_hangup), Red, onHangUp, enabled = call.phase != CallPhase.Ended)
            }
        }
    }
}

/** A WebRTC video track drawn on a SurfaceView. [overlay] puts it above another video view. */
@Composable
private fun VideoView(track: VideoTrack?, egl: EglBase.Context, mirror: Boolean, modifier: Modifier, overlay: Boolean = false) {
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(egl, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                if (overlay) setZOrderMediaOverlay(true)
                renderer = this
            }
        },
        update = { it.setMirror(mirror) },
        onRelease = { it.release() },
        modifier = modifier,
    )
    DisposableEffect(track, renderer) {
        val r = renderer
        if (r != null) track?.addSink(r)
        onDispose { if (r != null) track?.removeSink(r) }
    }
}

@Composable
private fun SmallAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(22.dp))
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
        CallPhase.Incoming -> stringResource(if (call.video) R.string.call_incoming_video else R.string.call_incoming)
        CallPhase.Connecting -> stringResource(R.string.call_connecting)
        CallPhase.Active -> if (call.reconnecting) stringResource(R.string.call_reconnecting)
        else if (call.onHold) stringResource(R.string.call_on_hold)
        else if (call.peerOnHold) stringResource(R.string.call_peer_on_hold)
        else {
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
                "e2e_failed" -> R.string.call_end_e2e
                "network" -> R.string.err_network
                "in_call" -> R.string.call_end_in_call
                else -> R.string.call_ended
            }
        )
    }
}

/** Round trip of the audio: what the delay in the conversation feels like. */
@Composable
private fun QualityChip(rttMs: Int) {
    val (color, label) = when {
        rttMs < 150 -> Color(0xFF22C55E) to R.string.quality_excellent
        rttMs < 300 -> Color(0xFF84CC16) to R.string.quality_good
        rttMs < 500 -> Color(0xFFF59E0B) to R.string.quality_fair
        else -> Color(0xFFEF4444) to R.string.quality_poor
    }
    val text = stringResource(R.string.call_quality, stringResource(label), rttMs)
    Row(
        Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.25f))
            .semantics(mergeDescendants = true) { contentDescription = text }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelLarge)
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
