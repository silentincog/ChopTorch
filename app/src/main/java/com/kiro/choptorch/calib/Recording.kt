package com.kiro.choptorch.calib

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/** Raw accelerometer samples captured during calibration. Written and read on one thread. */
class Recording {
    var size = 0
        private set
    var ts = LongArray(4096); private set
    var x = FloatArray(4096); private set
    var y = FloatArray(4096); private set
    var z = FloatArray(4096); private set

    fun add(ax: Float, ay: Float, az: Float, tsNs: Long) {
        if (size == ts.size) {
            val n = size * 2
            ts = ts.copyOf(n); x = x.copyOf(n); y = y.copyOf(n); z = z.copyOf(n)
        }
        ts[size] = tsNs; x[size] = ax; y[size] = ay; z[size] = az
        size++
    }

    val durationMs: Long get() = if (size < 2) 0L else (ts[size - 1] - ts[0]) / 1_000_000L

    /** Average samples per second the sensor actually delivered. */
    val rateHz: Float get() = if (durationMs == 0L) 0f else (size - 1) * 1000f / durationMs

    /** Largest absolute value on any axis (to spot sensor saturation). */
    fun maxAbsComponent(): Float {
        var m = 0f
        for (i in 0 until size) m = maxOf(m, abs(x[i]), abs(y[i]), abs(z[i]))
        return m
    }
}

/**
 * Splits a recording into separate gestures ("bursts") separated by quiet pauses.
 * Fed one sample at a time so the UI can count gestures live.
 */
class BurstSegmenter {
    data class Burst(val startMs: Long, val endMs: Long)

    companion object {
        const val START = 6f          // m/s²: activity above this opens a burst
        const val END = 3f            // m/s²: below this counts as quiet
        const val QUIET_MS = 1100L    // this much quiet closes the burst (longer than the pause inside a slow double chop)
        const val MIN_LEN_MS = 30L    // shorter bumps are discarded
        const val TAU_S = 0.2f        // fast gravity estimate, always updating (handles rotation)
    }

    val bursts = ArrayList<Burst>()
    private val g = FloatArray(3)
    private var ready = false
    private var lastTsNs = 0L
    private var active = false
    private var startMs = 0L
    private var lastLoudMs = 0L

    val isActive: Boolean get() = active

    /** @return true when this sample closed a new burst. */
    fun onSample(ax: Float, ay: Float, az: Float, tsNs: Long): Boolean {
        val now = tsNs / 1_000_000L
        if (!ready) {
            g[0] = ax; g[1] = ay; g[2] = az
            ready = true
            lastTsNs = tsNs
            return false
        }
        val dt = ((tsNs - lastTsNs) / 1e9f).coerceIn(0f, 0.2f)
        lastTsNs = tsNs
        val k = 1f - exp(-dt / TAU_S)
        g[0] += (ax - g[0]) * k
        g[1] += (ay - g[1]) * k
        g[2] += (az - g[2]) * k
        val lx = ax - g[0]; val ly = ay - g[1]; val lz = az - g[2]
        val lin = sqrt(lx * lx + ly * ly + lz * lz)

        if (!active) {
            if (lin > START) {
                active = true
                startMs = now
                lastLoudMs = now
            }
            return false
        }
        if (lin > END) lastLoudMs = now
        return if (now - lastLoudMs >= QUIET_MS) close() else false
    }

    /** Closes a burst that is still in progress (user tapped Finish mid-gesture). */
    fun flush(): Boolean = if (active) close() else false

    private fun close(): Boolean {
        active = false
        if (lastLoudMs - startMs < MIN_LEN_MS) return false
        bursts += Burst(startMs, lastLoudMs)
        return true
    }
}
