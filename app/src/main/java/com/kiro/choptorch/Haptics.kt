package com.kiro.choptorch

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * How strong the confirmation buzz is. Strength comes mostly from length: budget phones such as
 * the Redmi 10 Prime usually have a simple motor without amplitude control, which needs about
 * 50–80 ms just to spin up, so very short pulses are barely felt. Amplitude is used only if the
 * motor supports it.
 */
enum class Strength(val label: String, val onMs: Long, val pulseMs: Long, val gapMs: Long, val amplitude: Int) {
    LIGHT("Light", 80, 60, 90, 150),
    MEDIUM("Medium", 150, 100, 110, 220),
    STRONG("Strong", 250, 160, 120, 255),
}

/** One buzz for torch on, two for off; the same patterns are used for calibration cues. */
enum class Buzz { ON, OFF, TICK }

object Haptics {
    /**
     * Android 13+ treats a vibration without a usage as "unknown": it is scaled by the *media*
     * vibration setting and dropped entirely in battery saver. Hardware feedback (what the
     * fingerprint sensor uses) follows the touch feedback strength, is never fully turned off by
     * that setting, works in battery saver and silent mode, and is allowed from a background
     * service, which is what this is: feedback to a physical gesture.
     */
    const val USAGE = VibrationAttributes.USAGE_HARDWARE_FEEDBACK

    fun vibrator(ctx: Context): Vibrator? {
        val v = if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Vibrator::class.java)
        }
        return v?.takeIf { it.hasVibrator() }
    }

    fun effect(buzz: Buzz, s: Strength, amplitudeControl: Boolean): VibrationEffect {
        val amp = if (amplitudeControl) s.amplitude else VibrationEffect.DEFAULT_AMPLITUDE
        return when (buzz) {
            Buzz.ON -> VibrationEffect.createOneShot(s.onMs, amp)
            Buzz.TICK -> VibrationEffect.createOneShot((s.onMs * 6 / 10).coerceAtLeast(60), amp)
            Buzz.OFF -> VibrationEffect.createWaveform(
                longArrayOf(0, s.pulseMs, s.gapMs, s.pulseMs),
                intArrayOf(0, amp, 0, amp),
                -1,
            )
        }
    }

    fun play(ctx: Context, buzz: Buzz, s: Strength = Prefs.vibrationStrength(ctx)) {
        val v = vibrator(ctx) ?: return
        val e = effect(buzz, s, v.hasAmplitudeControl())
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(e, VibrationAttributes.createForUsage(USAGE))
        } else {
            v.vibrate(e)
        }
    }
}
