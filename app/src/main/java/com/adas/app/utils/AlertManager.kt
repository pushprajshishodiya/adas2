package com.adas.app.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.adas.app.detection.WarningLevel

class AlertManager(context: Context) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .build()
        ).build()

    private var lastAlertLevel = WarningLevel.SAFE
    private var lastAlertTimeMs = 0L
    private val cooldownMs = mapOf(
        WarningLevel.DANGER  to 800L,
        WarningLevel.WARNING to 1500L,
        WarningLevel.CAUTION to 3000L,
        WarningLevel.SAFE    to Long.MAX_VALUE
    )

    fun alert(level: WarningLevel) {
        val now = System.currentTimeMillis()
        val cd = cooldownMs[level] ?: Long.MAX_VALUE
        if (level == WarningLevel.SAFE) return
        if (level == lastAlertLevel && now - lastAlertTimeMs < cd) return

        lastAlertLevel = level
        lastAlertTimeMs = now
        vibrate(level)
    }

    private fun vibrate(level: WarningLevel) {
        val pattern: LongArray
        val amplitudes: IntArray
        when (level) {
            WarningLevel.DANGER  -> { pattern = longArrayOf(0, 120, 80, 120, 80, 200); amplitudes = intArrayOf(0, 255, 0, 255, 0, 255) }
            WarningLevel.WARNING -> { pattern = longArrayOf(0, 100, 100, 100);          amplitudes = intArrayOf(0, 200, 0, 200) }
            WarningLevel.CAUTION -> { pattern = longArrayOf(0, 60);                     amplitudes = intArrayOf(0, 140) }
            else -> return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    fun release() {
        soundPool.release()
    }
}
