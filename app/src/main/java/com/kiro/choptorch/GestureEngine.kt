package com.kiro.choptorch

/** Anything that turns accelerometer samples into "toggle the torch" decisions. */
interface GestureEngine {
    fun onSample(ax: Float, ay: Float, az: Float, tsNs: Long)

    /** How long gestures are ignored after one switched the torch (ms). Safe to change at any time. */
    var cooldownMs: Long

    /** Largest linear-acceleration magnitude since the last call (for the Live force readout). */
    fun takePeak(): Float

    /**
     * Start fresh after the sensor was paused: forget any half-seen gesture and re-measure
     * gravity from the next sample, because the phone may now be held a different way.
     */
    fun restart()
}

/** Which engine the service runs. */
/**
 * Which engine the service runs.
 * AUTO: classic detector that tunes its own force and trains the personal model when ready.
 * CLASSIC: classic detector with the force you set (Manual).
 * MODEL: the personal model.
 */
enum class Engine { AUTO, CLASSIC, MODEL }
