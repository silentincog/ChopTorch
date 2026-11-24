package dev.incog.choptorch

import java.util.Locale
import kotlin.math.exp
import kotlin.math.sqrt

/** The tunable part of the gesture. Calibration picks these from the user's own recording. */
data class DetectorParams(
    /** Linear acceleration (m/s²) a swing must exceed. Lower = more sensitive. */
    val threshold: Float = ChopDetector.DEFAULT_THRESHOLD,
    /** true = "chop" mode: the phone must calm down between the two chops. false = "shake" mode. */
    val requireSettle: Boolean = true,
    /** Longest time between the start of chop 1 and the start of chop 2. */
    val maxGapMs: Long = ChopDetector.DEFAULT_MAX_GAP_MS,
    /** Longest time from the start of a swing to its sharp stop. */
    val maxSwingMs: Long = ChopDetector.DEFAULT_MAX_SWING_MS,
) {
    val isDefaultTiming: Boolean
        get() = requireSettle &&
            maxGapMs == ChopDetector.DEFAULT_MAX_GAP_MS &&
            maxSwingMs == ChopDetector.DEFAULT_MAX_SWING_MS

    val modeLabel: String get() = if (requireSettle) "Chop mode" else "Shake mode"
    val gapLabel: String get() = String.format(Locale.US, "Up to %.1f s between chops", maxGapMs / 1000f)
    val swingLabel: String get() = if (maxSwingMs > ChopDetector.DEFAULT_MAX_SWING_MS) "Slow swings allowed" else "Quick swings"

    /** Machine-friendly one-liner for logs and reports. */
    fun summary(): String = String.format(
        Locale.US, "trigger %.2f m/s², %s, max gap %d ms, max swing %d ms",
        threshold, if (requireSettle) "chop mode" else "shake mode", maxGapMs, maxSwingMs,
    )
}

/**
 * Detects a Motorola-style "chop chop" from raw accelerometer samples.
 *
 * Pure Kotlin (no Android imports) so the same code runs in the service, in calibration replays,
 * and on the JVM.
 *
 * One chop = a hard swing (linear acceleration above the threshold in some direction d) followed
 * within maxSwingMs by a hard stop (acceleration along -d above threshold * [BRAKE_RATIO]).
 * Two chops in roughly the same direction, starting [MIN_GAP_MS]..maxGapMs apart, fire
 * [onDoubleChop]. In chop mode the phone must calm down between the chops; shake mode skips that.
 */
class ChopDetector(
    @Volatile var params: DetectorParams = DetectorParams(),
    private val onDoubleChop: () -> Unit,
    private val onEvent: ((Event) -> Unit)? = null,
) : GestureEngine {
    enum class Kind { CHOP1, FIRE, NO_STOP, NO_SETTLE, NO_SECOND, GAP_OUT }

    class Event(val kind: Kind, val tMs: Long, val peak: Float = 0f, val gapMs: Long = 0L) {
        fun text(): String = when (kind) {
            Kind.CHOP1 -> "chop 1 (peak %.1f)".format(peak)
            Kind.FIRE -> "chop 2 (peak %.1f, gap %d ms) -> TORCH".format(peak, gapMs)
            Kind.NO_STOP -> "swing without a sharp stop, reset"
            Kind.NO_SETTLE -> "kept moving after chop 1, reset"
            Kind.NO_SECOND -> "no second chop in time"
            Kind.GAP_OUT -> "chops %d ms apart, too close, restart".format(gapMs)
        }
    }

    companion object {
        const val DEFAULT_THRESHOLD = 14f      // m/s² of linear acceleration (~1.4 g)
        const val DEFAULT_MAX_GAP_MS = 900L
        const val DEFAULT_MAX_SWING_MS = 350L
        const val BRAKE_RATIO = 0.5f           // stop must reach 50 % of threshold, opposite direction
        const val SETTLE_RATIO = 0.5f          // signal must calm below this between chops (chop mode)
        const val SAME_DIR_COS = 0.6f          // second chop must point the same way (cos angle)
        const val MAX_SETTLE_MS = 600L
        const val MIN_GAP_MS = 150L            // chop1 start -> chop2 start
        const val COOLDOWN_MS = 1200L          // default delay between toggles
        const val MIN_COOLDOWN_MS = 300L
        const val MAX_COOLDOWN_MS = 2000L
        const val GRAVITY_TAU_S = 0.25f        // low-pass time constant for gravity estimate
    }

    private enum class State { IDLE, SWING, SETTLE, WAIT_SECOND }

    private var state = State.IDLE
    private val gravity = FloatArray(3)
    private var gravityReady = false
    private var lastTsNs = 0L

    private val swingDir = FloatArray(3)
    private val firstDir = FloatArray(3)
    private var swingStartMs = 0L
    private var swingPeak = 0f
    private var firstChopMs = 0L
    private var chopCount = 0
    private var lastFireMs = Long.MIN_VALUE / 2

    /** Linear-acceleration magnitude of the most recent sample (m/s²). */
    var lastMag = 0f
        private set

    /** Largest linear-acceleration magnitude seen since the last call to [takePeak]. */
    @Volatile private var peakSinceRead = 0f

    override fun takePeak(): Float = peakSinceRead.also { peakSinceRead = 0f }

    @Volatile override var cooldownMs: Long = COOLDOWN_MS

    override fun restart() {
        reset()
        gravityReady = false
    }

    fun reset() {
        state = State.IDLE
        chopCount = 0
    }

    /** @param tsNs sensor timestamp in nanoseconds (SensorEvent.timestamp). */
    override fun onSample(ax: Float, ay: Float, az: Float, tsNs: Long) {
        val nowMs = tsNs / 1_000_000L
        if (!gravityReady) {
            gravity[0] = ax; gravity[1] = ay; gravity[2] = az
            gravityReady = true
            lastTsNs = tsNs
            return
        }
        val dt = ((tsNs - lastTsNs) / 1e9f).coerceIn(0f, 0.2f)
        lastTsNs = tsNs

        // Freeze the gravity estimate while a chop is in progress so the swing is not absorbed.
        if (state == State.IDLE || state == State.WAIT_SECOND) {
            val k = 1f - exp(-dt / GRAVITY_TAU_S)
            gravity[0] += (ax - gravity[0]) * k
            gravity[1] += (ay - gravity[1]) * k
            gravity[2] += (az - gravity[2]) * k
        }

        val lx = ax - gravity[0]
        val ly = ay - gravity[1]
        val lz = az - gravity[2]
        val mag = sqrt(lx * lx + ly * ly + lz * lz)
        lastMag = mag
        if (mag > peakSinceRead) peakSinceRead = mag

        val p = params
        val t = p.threshold
        when (state) {
            State.IDLE -> {
                if (nowMs - lastFireMs < cooldownMs) return
                if (mag > t) startSwing(lx, ly, lz, mag, nowMs)
            }

            State.SWING -> {
                val along = lx * swingDir[0] + ly * swingDir[1] + lz * swingDir[2]
                if (along > swingPeak) swingPeak = along
                when {
                    along < -t * BRAKE_RATIO -> completeChop(nowMs, p)
                    nowMs - swingStartMs > p.maxSwingMs -> {
                        emit(Kind.NO_STOP, nowMs, swingPeak)
                        reset()
                    }
                }
            }

            State.SETTLE -> {
                if (mag < t * SETTLE_RATIO) {
                    state = State.WAIT_SECOND
                } else if (nowMs - firstChopMs > MAX_SETTLE_MS) {
                    emit(Kind.NO_SETTLE, nowMs)
                    reset()
                }
            }

            State.WAIT_SECOND -> {
                if (nowMs - firstChopMs > p.maxGapMs) {
                    emit(Kind.NO_SECOND, nowMs)
                    reset()
                    return
                }
                if (mag > t) {
                    val cos = (lx * firstDir[0] + ly * firstDir[1] + lz * firstDir[2]) / mag
                    // Opposite-direction spikes are the arm coming back up: ignore them.
                    if (cos > SAME_DIR_COS) startSwing(lx, ly, lz, mag, nowMs)
                }
            }
        }
    }

    private fun startSwing(lx: Float, ly: Float, lz: Float, mag: Float, nowMs: Long) {
        swingDir[0] = lx / mag; swingDir[1] = ly / mag; swingDir[2] = lz / mag
        swingStartMs = nowMs
        swingPeak = mag
        state = State.SWING
    }

    private fun completeChop(nowMs: Long, p: DetectorParams) {
        chopCount++
        val next = if (p.requireSettle) State.SETTLE else State.WAIT_SECOND
        if (chopCount == 1) {
            firstChopMs = swingStartMs
            System.arraycopy(swingDir, 0, firstDir, 0, 3)
            emit(Kind.CHOP1, nowMs, swingPeak)
            state = next
            return
        }
        val gap = swingStartMs - firstChopMs
        if (gap in MIN_GAP_MS..p.maxGapMs) {
            emit(Kind.FIRE, nowMs, swingPeak, gap)
            lastFireMs = nowMs
            reset()
            onDoubleChop()
        } else {
            // Too close together: treat this one as a new first chop.
            emit(Kind.GAP_OUT, nowMs, swingPeak, gap)
            chopCount = 1
            firstChopMs = swingStartMs
            System.arraycopy(swingDir, 0, firstDir, 0, 3)
            state = next
        }
    }

    private fun emit(kind: Kind, tMs: Long, peak: Float = 0f, gapMs: Long = 0L) {
        onEvent?.invoke(Event(kind, tMs, peak, gapMs))
    }
}
