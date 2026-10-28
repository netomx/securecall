package com.cim.securecall.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AudioRoute { Earpiece, Speaker, Bluetooth }

/**
 * Call audio: communication mode, audio focus, earpiece / speaker / Bluetooth headset,
 * and the proximity sensor that turns the screen off while the phone is at the ear.
 */
@Suppress("DEPRECATION")  // legacy speaker/SCO APIs are only used below Android 12
class AudioRouter(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val proximity = context.getSystemService(PowerManager::class.java)
        .takeIf { it.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) }
        ?.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "kalloq:proximity")
    private var focus: AudioFocusRequest? = null
    private var active = false

    private val _route = MutableStateFlow(AudioRoute.Earpiece)
    val route: StateFlow<AudioRoute> = _route

    private val _bluetooth = MutableStateFlow(false)
    val bluetoothAvailable: StateFlow<Boolean> = _bluetooth

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = devicesChanged()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = devicesChanged()
    }

    fun start() {
        if (active) return
        active = true
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
            .also { am.requestAudioFocus(it) }
        am.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        _bluetooth.value = findBluetooth()
        setRoute(if (_bluetooth.value) AudioRoute.Bluetooth else AudioRoute.Earpiece)
    }

    fun stop() {
        if (!active) return
        active = false
        am.unregisterAudioDeviceCallback(deviceCallback)
        releaseProximity()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.clearCommunicationDevice()
        } else {
            am.isSpeakerphoneOn = false
            am.stopBluetoothSco()
            am.isBluetoothScoOn = false
        }
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
        am.mode = AudioManager.MODE_NORMAL
        _route.value = AudioRoute.Earpiece
    }

    fun setRoute(route: AudioRoute) {
        val target = if (route == AudioRoute.Bluetooth && !_bluetooth.value) AudioRoute.Earpiece else route
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val type = when (target) {
                AudioRoute.Earpiece -> AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                AudioRoute.Speaker -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                AudioRoute.Bluetooth -> null
            }
            val device = am.availableCommunicationDevices.firstOrNull {
                if (type == null) it.type in BLUETOOTH_TYPES else it.type == type
            }
            if (device != null) am.setCommunicationDevice(device)
        } else {
            when (target) {
                AudioRoute.Speaker -> {
                    am.stopBluetoothSco()
                    am.isBluetoothScoOn = false
                    am.isSpeakerphoneOn = true
                }
                AudioRoute.Bluetooth -> {
                    am.isSpeakerphoneOn = false
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                }
                AudioRoute.Earpiece -> {
                    am.stopBluetoothSco()
                    am.isBluetoothScoOn = false
                    am.isSpeakerphoneOn = false
                }
            }
        }
        _route.value = target
        // Screen off near the face only when the audio comes out of the earpiece.
        if (target == AudioRoute.Earpiece) acquireProximity() else releaseProximity()
    }

    private fun devicesChanged() {
        if (!active) return
        val available = findBluetooth()
        val was = _bluetooth.value
        _bluetooth.value = available
        when {
            available && !was -> setRoute(AudioRoute.Bluetooth)  // headset just connected: use it
            !available && _route.value == AudioRoute.Bluetooth -> setRoute(AudioRoute.Earpiece)
        }
    }

    private fun findBluetooth(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.availableCommunicationDevices.any { it.type in BLUETOOTH_TYPES }
        } else {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        }

    private fun acquireProximity() {
        proximity?.takeIf { !it.isHeld }?.acquire(4 * 60 * 60 * 1000L)
    }

    private fun releaseProximity() {
        proximity?.takeIf { it.isHeld }?.release()
    }

    private companion object {
        val BLUETOOTH_TYPES = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }
}
