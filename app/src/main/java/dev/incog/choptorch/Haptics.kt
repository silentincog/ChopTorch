package dev.incog.choptorch

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Timing of the confirmation buzz.
 *
 * @param onMs length of the single buzz when the torch turns on
 * @param pulseMs length of each of the two buzzes when it turns off
 * @param gapMs pause between those two buzzes
 * @param amplitude 1–255; only used if the motor supports amplitude control
 */
data class VibrationPattern(val onMs: Long, val pulseMs: Long, val gapMs: Long, val amplitude: Int) {
    /** The short cue used during calibration. */
    val tickMs: Long get() = (onMs * 6 / 10).coerceIn(minOf(60L, onMs), onMs)

    fun clamped() = VibrationPattern(
        onMs.coerceIn(ON_RANGE.first, ON_RANGE.last),
        pulseMs.coerceIn(PULSE_RANGE.first, PULSE_RANGE.last),
        gapMs.coerceIn(GAP_RANGE.first, GAP_RANGE.last),
        amplitude.coerceIn(AMP_RANGE.first, AMP_RANGE.last),
    )

    companion object {
        val ON_RANGE = 20L..600L
        val PULSE_RANGE = 20L..400L
        val GAP_RANGE = 40L..400L
        val AMP_RANGE = 10..255
        const val STEP_MS = 10L
    }
}

/**
 * How strong the confirmation buzz is. Strength comes mostly from length: budget phones such as
 * the Redmi 10 Prime usually have a simple motor without amplitude control, which needs about
 * 50–80 ms just to spin up, so very short pulses are barely felt. [CUSTOM] uses your own timings.
 */
enum class Strength(val label: String, val preset: VibrationPattern?) {
    LIGHT("Light", VibrationPattern(80, 60, 90, 150)),
    MEDIUM("Medium", VibrationPattern(150, 100, 110, 220)),
    STRONG("Strong", VibrationPattern(250, 160, 120, 255)),
    CUSTOM("Custom", null),
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

    /** True if the motor can vary its strength (most budget phones can't, only on/off). */
    fun hasAmplitudeControl(ctx: Context): Boolean = vibrator(ctx)?.hasAmplitudeControl() == true

    fun effect(buzz: Buzz, p: VibrationPattern, amplitudeControl: Boolean): VibrationEffect {
        val amp = if (amplitudeControl) p.amplitude else VibrationEffect.DEFAULT_AMPLITUDE
        return when (buzz) {
            Buzz.ON -> VibrationEffect.createOneShot(p.onMs, amp)
            Buzz.TICK -> VibrationEffect.createOneShot(p.tickMs, amp)
            Buzz.OFF -> VibrationEffect.createWaveform(
                longArrayOf(0, p.pulseMs, p.gapMs, p.pulseMs),
                intArrayOf(0, amp, 0, amp),
                -1,
            )
        }
    }

    fun play(ctx: Context, buzz: Buzz, p: VibrationPattern = Prefs.vibrationPattern(ctx)) {
        val v = vibrator(ctx) ?: return
        val e = effect(buzz, p, v.hasAmplitudeControl())
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(e, VibrationAttributes.createForUsage(USAGE))
        } else {
            v.vibrate(e)
        }
    }
}
