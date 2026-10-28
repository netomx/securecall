package com.cim.securecall.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/** Ringtone + vibration for incoming calls, ringback tone while we call someone. */
class CallTones(private val context: Context) {
    private var ringtone: Ringtone? = null
    private var ringback: ToneGenerator? = null
    @Suppress("DEPRECATION")
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val am = context.getSystemService(AudioManager::class.java)

    fun ring() {
        stop()
        if (am.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            ringtone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        }
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 1200), 0))
    }

    fun ringback() {
        stop()
        ringback = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70) }.getOrNull()?.apply {
            startTone(ToneGenerator.TONE_SUP_RINGTONE)
        }
    }

    fun stop() {
        ringtone?.stop()
        ringtone = null
        ringback?.stopTone()
        ringback?.release()
        ringback = null
        vibrator?.cancel()
    }
}
