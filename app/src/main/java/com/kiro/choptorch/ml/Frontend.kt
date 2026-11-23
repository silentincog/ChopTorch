package com.kiro.choptorch.ml

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/** One fixed-length slice of motion that starts at a movement onset. */
class Window(
    /** Interleaved x, y, z linear acceleration at 50 Hz: [x0, y0, z0, x1, ...]. */
    val data: FloatArray,
    val frames: Int,
    /** Largest per-frame magnitude in the window (m/s²). */
    val peak: Float,
    val onsetMs: Long,
    val endMs: Long,
)

/**
 * Turns raw accelerometer samples into gesture-sized windows.
 *
 * Training and live detection both use this, so the model always sees motion processed the same
 * way: gravity removed with an always-updating low-pass, then resampled into 20 ms frames on a
 * time grid anchored at the exact (interpolated) moment the movement crossed [onsetLevel]. The
 * anchoring matters: a fixed clock grid would slice the same gesture differently depending on
 * when it happened.
 */
class Frontend(
    private val onsetLevel: Float,
    private val windowFrames: Int,
    private val onWindow: (Window) -> Unit,
) {
    companion object {
        const val FRAME_MS = 20L
        const val PRE_MS = 40L
        const val MIN_ONSET_GAP_MS = 120L
        const val GRAVITY_TAU_S = 0.6f
        private const val RING = 1024
        private const val FRAME_NS = FRAME_MS * 1_000_000L
        private val TAPS_NS = longArrayOf(-7_500_000L, -2_500_000L, 2_500_000L, 7_500_000L)
    }

    private val g = FloatArray(3)
    private var ready = false
    private var lastTsNs = 0L
    private var prevMag = 0f

    // Raw linear acceleration history, addressed by a running sample counter.
    private val rt = LongArray(RING)
    private val rx = FloatArray(RING)
    private val ry = FloatArray(RING)
    private val rz = FloatArray(RING)
    private var count = 0L

    private val open = ArrayDeque<Long>() // window start times (ns)
    private var lastOnsetNs = Long.MIN_VALUE / 2
    /** A new onset needs the motion to calm below half the onset level first. */
    private var rearmed = true

    /** Linear-acceleration magnitude of the latest sample. */
    var lastMag = 0f
        private set
    @Volatile private var peakSinceRead = 0f

    fun takePeak(): Float = peakSinceRead.also { peakSinceRead = 0f }

    /** Forget open windows and re-measure gravity from the next sample. */
    fun reset() {
        open.clear()
        ready = false
        rearmed = true
        prevMag = 0f
    }

    fun onSample(ax: Float, ay: Float, az: Float, tsNs: Long) {
        if (!ready) {
            g[0] = ax; g[1] = ay; g[2] = az
            ready = true
            lastTsNs = tsNs
            return
        }
        if (tsNs <= lastTsNs) return
        val dt = ((tsNs - lastTsNs) / 1e9f).coerceIn(0f, 0.2f)
        val prevTs = lastTsNs
        lastTsNs = tsNs
        val k = 1f - exp(-dt / GRAVITY_TAU_S)
        g[0] += (ax - g[0]) * k
        g[1] += (ay - g[1]) * k
        g[2] += (az - g[2]) * k
        val lx = ax - g[0]; val ly = ay - g[1]; val lz = az - g[2]
        val mag = sqrt(lx * lx + ly * ly + lz * lz)
        lastMag = mag
        if (mag > peakSinceRead) peakSinceRead = mag

        val r = (count % RING).toInt()
        rt[r] = tsNs; rx[r] = lx; ry[r] = ly; rz[r] = lz
        count++

        if (mag < onsetLevel * 0.5f) rearmed = true
        if (rearmed && mag > onsetLevel && prevMag <= onsetLevel) {
            // Interpolate the exact crossing time between the previous and this sample.
            val f = ((onsetLevel - prevMag) / (mag - prevMag)).coerceIn(0f, 1f)
            val onsetNs = prevTs + ((tsNs - prevTs) * f).toLong()
            if (onsetNs - lastOnsetNs >= MIN_ONSET_GAP_MS * 1_000_000L) {
                lastOnsetNs = onsetNs
                open.addLast(onsetNs - PRE_MS * 1_000_000L)
                rearmed = false
            }
        }
        prevMag = mag

        // The last frame's averaging taps reach 7.5 ms past its centre.
        while (open.isNotEmpty() && tsNs >= open.first() + windowFrames * FRAME_NS) {
            val start = open.removeFirst()
            extract(start)?.let(onWindow)
        }
    }

    private fun extract(startNs: Long): Window? {
        val oldest = max(0L, count - RING)
        if (rt[(oldest % RING).toInt()] > startNs) return null // history too short
        val data = FloatArray(windowFrames * 3)
        var peak = 0f
        var cursor = oldest
        for (f in 0 until windowFrames) {
            val centre = startNs + f * FRAME_NS + FRAME_NS / 2
            var sx = 0f; var sy = 0f; var sz = 0f
            for (tap in TAPS_NS) {
                val q = centre + tap
                while (cursor + 1 < count && rt[((cursor + 1) % RING).toInt()] <= q) cursor++
                val i0 = (cursor % RING).toInt()
                val i1 = (minOf(cursor + 1, count - 1) % RING).toInt()
                val t0 = rt[i0]; val t1 = rt[i1]
                val w = if (t1 > t0) ((q - t0).toFloat() / (t1 - t0)).coerceIn(0f, 1f) else 0f
                sx += rx[i0] + (rx[i1] - rx[i0]) * w
                sy += ry[i0] + (ry[i1] - ry[i0]) * w
                sz += rz[i0] + (rz[i1] - rz[i0]) * w
            }
            val x = sx / 4f; val y = sy / 4f; val z = sz / 4f
            data[f * 3] = x; data[f * 3 + 1] = y; data[f * 3 + 2] = z
            val m = sqrt(x * x + y * y + z * z)
            if (m > peak) peak = m
        }
        val onsetMs = (startNs + PRE_MS * 1_000_000L) / 1_000_000L
        return Window(data, windowFrames, peak, onsetMs, (startNs + windowFrames * FRAME_NS) / 1_000_000L)
    }
}

/** Dynamic time warping between two windows of equal length, with a Sakoe-Chiba band. */
object Dtw {
    fun distance(a: FloatArray, b: FloatArray, frames: Int, band: Int): Float {
        val inf = Float.MAX_VALUE
        var prev = FloatArray(frames) { inf }
        var cur = FloatArray(frames) { inf }
        for (i in 0 until frames) {
            java.util.Arrays.fill(cur, inf)
            val jFrom = max(0, i - band)
            val jTo = minOf(frames - 1, i + band)
            for (j in jFrom..jTo) {
                val dx = a[i * 3] - b[j * 3]
                val dy = a[i * 3 + 1] - b[j * 3 + 1]
                val dz = a[i * 3 + 2] - b[j * 3 + 2]
                val cost = sqrt(dx * dx + dy * dy + dz * dz)
                val best = when {
                    i == 0 && j == 0 -> 0f
                    i == 0 -> cur[j - 1]
                    j == 0 -> prev[j]
                    else -> minOf(prev[j], cur[j - 1], prev[j - 1])
                }
                cur[j] = if (best == inf) inf else best + cost
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[frames - 1] / (2 * frames)
    }
}

/** Force-independent shape comparison: each window scaled to unit RMS, then DTW. */
object Shape {
    fun normalize(w: FloatArray): FloatArray {
        var sum = 0f
        for (v in w) sum += v * v
        val rms = sqrt(sum / (w.size / 3)).coerceAtLeast(0.5f)
        return FloatArray(w.size) { w[it] / rms }
    }

    fun band(frames: Int) = max(3, frames / 4)

    fun distance(a: FloatArray, b: FloatArray, frames: Int): Float =
        Dtw.distance(normalize(a), normalize(b), frames, band(frames))
}
