package com.kiro.choptorch

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val ENABLED = "enabled"
    const val THRESHOLD = "threshold"
    const val REQUIRE_SETTLE = "require_settle"
    const val MAX_GAP = "max_gap_ms"
    const val MAX_SWING = "max_swing_ms"
    const val SCREEN_OFF = "screen_off"
    const val ONLY_LOCKED = "only_locked"
    const val POCKET_GUARD = "pocket_guard"
    const val VIBRATE = "vibrate"
    const val ENGINE = "engine"
    const val MODEL_REV = "model_rev"
    const val LEARN = "learn_from_use"
    const val STRUGGLES = "struggles"
    const val AUTO_THRESHOLD = "auto_threshold"
    const val AUTO_FORCES = "auto_forces"
    const val AUTO_FALSE = "auto_false"
    const val AUTO_GESTURES = "auto_gestures"
    const val AUTO_EVERYDAY = "auto_everyday"
    const val AUTO_MODEL_AT = "auto_model_at"
    private const val MIGRATED_AUTO = "migrated_auto"
    private const val MIGRATED_LOCK = "migrated_lock_default"
    const val COOLDOWN = "cooldown_ms"

    /** Keys the screens watch to show Auto-mode progress. */
    val AUTO_KEYS = setOf(AUTO_THRESHOLD, AUTO_FORCES, AUTO_GESTURES, AUTO_EVERYDAY, AUTO_MODEL_AT)

    /** Keys that change how the gesture is detected. */
    val DETECTOR_KEYS = setOf(THRESHOLD, REQUIRE_SETTLE, MAX_GAP, MAX_SWING)

    fun get(ctx: Context): SharedPreferences =
        ctx.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences("chop", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = get(ctx).getBoolean(ENABLED, false)
    fun screenOff(ctx: Context) = get(ctx).getBoolean(SCREEN_OFF, true)
    /** Listen only while the phone is locked. Off by default: the gesture works locked and unlocked. */
    fun onlyLocked(ctx: Context): Boolean {
        val p = get(ctx)
        // 3.3 had this on by default; 3.4 turns it off once, so the gesture works everywhere again.
        if (!p.getBoolean(MIGRATED_LOCK, false)) {
            p.edit().putBoolean(MIGRATED_LOCK, true).putBoolean(ONLY_LOCKED, false).apply()
        }
        return p.getBoolean(ONLY_LOCKED, false)
    }

    /** Delay between toggles: how long gestures are ignored after one switched the torch. */
    fun cooldownMs(ctx: Context): Long =
        get(ctx).getInt(COOLDOWN, ChopDetector.COOLDOWN_MS.toInt()).toLong()
            .coerceIn(ChopDetector.MIN_COOLDOWN_MS, ChopDetector.MAX_COOLDOWN_MS)
    fun pocketGuard(ctx: Context) = get(ctx).getBoolean(POCKET_GUARD, true)
    fun vibrate(ctx: Context) = get(ctx).getBoolean(VIBRATE, true)

    fun engine(ctx: Context): Engine {
        val p = get(ctx)
        // 3.2: people on the classic detector move to Auto once; Manual stays one tap away.
        if (!p.getBoolean(MIGRATED_AUTO, false)) {
            val e = p.getString(ENGINE, null)
            p.edit().putBoolean(MIGRATED_AUTO, true).apply {
                if (e == null || e == Engine.CLASSIC.name) putString(ENGINE, Engine.AUTO.name)
            }.apply()
        }
        return runCatching { Engine.valueOf(p.getString(ENGINE, Engine.AUTO.name)!!) }.getOrDefault(Engine.AUTO)
    }

    fun setEngine(ctx: Context, e: Engine) = get(ctx).edit().putString(ENGINE, e.name).apply()

    /** Learn from everyday use (personal model only). */
    fun learn(ctx: Context) = get(ctx).getBoolean(LEARN, true)

    private const val DAY_MS = 24 * 60 * 60_000L

    /** Times in the last 24 h you had to use the torch by hand after it missed you. */
    fun struggles(ctx: Context): Int {
        val now = System.currentTimeMillis()
        return struggleTimes(ctx).count { now - it < DAY_MS }
    }

    fun addStruggle(ctx: Context) {
        val now = System.currentTimeMillis()
        val keep = struggleTimes(ctx).filter { now - it < DAY_MS } + now
        get(ctx).edit().putString(STRUGGLES, keep.takeLast(50).joinToString(",")).apply()
    }

    fun clearStruggles(ctx: Context) = get(ctx).edit().remove(STRUGGLES).apply()

    fun autoThreshold(ctx: Context) = get(ctx).getFloat(AUTO_THRESHOLD, ChopDetector.DEFAULT_THRESHOLD)
    fun autoForces(ctx: Context) = floats(get(ctx).getString(AUTO_FORCES, ""))
    fun autoFalse(ctx: Context) = floats(get(ctx).getString(AUTO_FALSE, ""))
    fun autoGestures(ctx: Context) = get(ctx).getInt(AUTO_GESTURES, 0)
    fun autoEveryday(ctx: Context) = get(ctx).getInt(AUTO_EVERYDAY, 0)
    fun autoModelAt(ctx: Context) = get(ctx).getLong(AUTO_MODEL_AT, 0L)

    fun saveAuto(ctx: Context, threshold: Float, forces: List<Float>, falseForces: List<Float>) {
        get(ctx).edit()
            .putFloat(AUTO_THRESHOLD, threshold)
            .putString(AUTO_FORCES, forces.joinToString(",") { "%.2f".format(java.util.Locale.US, it) })
            .putString(AUTO_FALSE, falseForces.joinToString(",") { "%.2f".format(java.util.Locale.US, it) })
            .apply()
    }

    fun resetAuto(ctx: Context) {
        get(ctx).edit()
            .remove(AUTO_THRESHOLD).remove(AUTO_FORCES).remove(AUTO_FALSE)
            .remove(AUTO_GESTURES).remove(AUTO_EVERYDAY).remove(AUTO_MODEL_AT)
            .apply()
    }

    private fun floats(s: String?): List<Float> = (s ?: "").split(',').mapNotNull { it.toFloatOrNull() }

    private fun struggleTimes(ctx: Context): List<Long> =
        (get(ctx).getString(STRUGGLES, "") ?: "").split(',').mapNotNull { it.toLongOrNull() }

    fun params(ctx: Context): DetectorParams {
        val p = get(ctx)
        return DetectorParams(
            threshold = p.getFloat(THRESHOLD, ChopDetector.DEFAULT_THRESHOLD),
            requireSettle = p.getBoolean(REQUIRE_SETTLE, true),
            maxGapMs = p.getInt(MAX_GAP, ChopDetector.DEFAULT_MAX_GAP_MS.toInt()).toLong(),
            maxSwingMs = p.getInt(MAX_SWING, ChopDetector.DEFAULT_MAX_SWING_MS.toInt()).toLong(),
        )
    }

    fun saveParams(ctx: Context, d: DetectorParams) {
        get(ctx).edit()
            .putFloat(THRESHOLD, d.threshold)
            .putBoolean(REQUIRE_SETTLE, d.requireSettle)
            .putInt(MAX_GAP, d.maxGapMs.toInt())
            .putInt(MAX_SWING, d.maxSwingMs.toInt())
            .apply()
    }

    // "My preset": the user's own saved setting, usually from calibration.
    private const val PRESET = "preset_"

    fun myPreset(ctx: Context): DetectorParams? {
        val p = get(ctx)
        if (!p.getBoolean(PRESET + "exists", false)) return null
        return DetectorParams(
            threshold = p.getFloat(PRESET + THRESHOLD, ChopDetector.DEFAULT_THRESHOLD),
            requireSettle = p.getBoolean(PRESET + REQUIRE_SETTLE, true),
            maxGapMs = p.getInt(PRESET + MAX_GAP, ChopDetector.DEFAULT_MAX_GAP_MS.toInt()).toLong(),
            maxSwingMs = p.getInt(PRESET + MAX_SWING, ChopDetector.DEFAULT_MAX_SWING_MS.toInt()).toLong(),
        )
    }

    /** Free-text origin shown under the preset, e.g. "Calibrated · Balanced · avg 21.3 m/s²". */
    fun myPresetNote(ctx: Context): String = get(ctx).getString(PRESET + "note", "") ?: ""

    fun saveMyPreset(ctx: Context, d: DetectorParams, note: String) {
        get(ctx).edit()
            .putBoolean(PRESET + "exists", true)
            .putFloat(PRESET + THRESHOLD, d.threshold)
            .putBoolean(PRESET + REQUIRE_SETTLE, d.requireSettle)
            .putInt(PRESET + MAX_GAP, d.maxGapMs.toInt())
            .putInt(PRESET + MAX_SWING, d.maxSwingMs.toInt())
            .putString(PRESET + "note", note)
            .apply()
    }
}
