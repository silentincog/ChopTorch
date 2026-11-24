package dev.incog.choptorch.calib

import dev.incog.choptorch.ChopDetector
import dev.incog.choptorch.DetectorParams
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

data class RepResult(
    val number: Int,
    val peak: Float,
    val ignored: Boolean,
    val recognised: Boolean,
    val detail: String,
)

/** How close to the user's average force the trigger sits. */
enum class Level(val label: String, val factor: Float, val hint: String) {
    LIGHT("Light", 0.60f, "Triggers on softer chops too"),
    BALANCED("Balanced", 0.75f, "Comfortable margin below your average"),
    FIRM("Firm", 0.90f, "Needs close to your average force"),
}

data class CalResult(
    /** Recommended (Balanced) setting, or null when nothing worked well enough to apply. */
    val params: DetectorParams?,
    val reps: List<RepResult>,
    val recognised: Int,
    /** Gestures that took part in scoring (recorded minus ignored). */
    val counted: Int,
    val extraTriggers: Int,
    /** Trigger forces (m/s²) that recognised the most gestures; NaN when none. */
    val rangeLo: Float,
    val rangeHi: Float,
    val headline: String,
    val warnings: List<String>,
    val sampleRateHz: Float,
    /** Mean peak force of the counted gestures, as the Live force readout shows it (m/s²). */
    val averagePeak: Float = Float.NaN,
    /** Mean of the highest trigger force each gesture still passes (m/s²); levels scale this. */
    val averageStrength: Float = Float.NaN,
    /** One setting per [Level], in enum order; empty when [params] is null. */
    val levels: List<DetectorParams> = emptyList(),
) {
    fun paramsFor(level: Level): DetectorParams? = levels.getOrNull(level.ordinal) ?: params
}

/**
 * Finds the detector setting that fits the user's own gesture.
 *
 * The recording is replayed through the real [ChopDetector] for every trigger force on a grid
 * and for several timing variants. That gives the range of trigger forces that recognises the
 * most gestures with no extra triggers. Each gesture's strength is the highest trigger force it
 * still passes; the trigger is the user's average strength times the [Level] factor, kept inside
 * that working range.
 */
object Calibrator {
    data class Config(val requireSettle: Boolean, val maxGapMs: Long, val maxSwingMs: Long)

    /** Strictest first: a looser variant is chosen only if it recognises more. */
    val CONFIGS = listOf(
        Config(true, 900, 350), Config(true, 1200, 350), Config(true, 900, 500), Config(true, 1200, 500),
        Config(false, 900, 350), Config(false, 1200, 350), Config(false, 900, 500), Config(false, 1200, 500),
    )

    const val T_MIN = 5f
    const val T_MAX = 50f
    const val T_STEP = 0.25f
    private const val DIAG_RATIO = 0.55f   // trigger used to explain gestures when nothing works
    const val MIN_REPS = 3
    const val IGNORE_RATIO = 0.5f
    const val SLIDER_MIN = 6f
    const val SLIDER_MAX = 40f
    private const val WIN_BEFORE_MS = 150L
    private const val WIN_AFTER_MS = 250L

    private val grid: FloatArray =
        FloatArray(((T_MAX - T_MIN) / T_STEP).roundToInt() + 1) { T_MIN + it * T_STEP }

    private class Score(val hit: BooleanArray, val extras: Int) {
        val hits: Int = hit.count { it }
        val value: Int get() = hits - 2 * extras
    }

    fun analyze(rec: Recording, bursts: List<BurstSegmenter.Burst>, sensorMaxRange: Float = 0f): CalResult {
        val rate = rec.rateHz
        val peaks = burstPeaks(rec, bursts)
        val baseWarnings = sensorWarnings(rec, rate, sensorMaxRange)

        if (bursts.size < MIN_REPS) {
            val reps = bursts.indices.map { RepResult(it + 1, peaks[it], false, false, "Recorded") }
            val headline = if (bursts.isEmpty()) "No gestures recorded"
            else "Only ${bursts.size} recorded. Do at least $MIN_REPS."
            return CalResult(null, reps, 0, bursts.size, 0, Float.NaN, Float.NaN, headline, baseWarnings, rate)
        }

        val median = peaks.sorted()[peaks.size / 2]
        val ignored = BooleanArray(bursts.size) { peaks[it] < IGNORE_RATIO * median }
        val counted = ignored.count { !it }
        val average = peaks.filterIndexed { i, _ -> !ignored[i] }.average().toFloat()

        val scores = CONFIGS.map { c -> grid.map { t -> score(rec, bursts, ignored, c.toParams(t)) } }
        val best = scores.maxOf { row -> row.maxOf { it.value } }

        var chosen: DetectorParams? = null
        var levels: List<DetectorParams> = emptyList()
        var strength = Float.NaN
        var lo = Float.NaN
        var hi = Float.NaN
        if (best >= 1) {
            val ci = scores.indexOfFirst { row -> row.any { it.value == best } }
            val row = scores[ci]
            val hiIdx = row.indexOfLast { it.value == best }
            var loIdx = hiIdx
            while (loIdx > 0 && row[loIdx - 1].value == best) loIdx--
            lo = grid[loIdx]
            hi = grid[hiIdx]
            val c = CONFIGS[ci]
            // Strength of each counted gesture: the highest trigger force it still passes.
            val strengths = bursts.indices.filter { !ignored[it] }.mapNotNull { i ->
                val top = row.indexOfLast { it.hit[i] }
                if (top >= 0) grid[top] else null
            }
            strength = if (strengths.isEmpty()) hi else strengths.average().toFloat()
            levels = Level.entries.map { c.toParams(pickThreshold(lo, hi, strength * it.factor)) }
            chosen = levels[Level.BALANCED.ordinal]
        }

        // Replay once more with the chosen (or a fallback) setting to explain each gesture.
        val diag = chosen ?: DetectorParams(threshold = (median * DIAG_RATIO).coerceIn(SLIDER_MIN, SLIDER_MAX))
        val events = replayEvents(rec, diag)
        val windows = bursts.map { (it.startMs - WIN_BEFORE_MS)..(it.endMs + WIN_AFTER_MS) }
        var extras = events.count { e -> e.kind == ChopDetector.Kind.FIRE && windows.none { e.tMs in it } }
        val reps = bursts.indices.map { i ->
            val inside = events.filter { it.tMs in windows[i] }
            val fires = inside.filter { it.kind == ChopDetector.Kind.FIRE }
            if (!ignored[i] && fires.size > 1) extras += fires.size - 1
            val recognised = !ignored[i] && fires.isNotEmpty()
            RepResult(
                number = i + 1,
                peak = peaks[i],
                ignored = ignored[i],
                recognised = recognised,
                detail = explain(ignored[i], fires.firstOrNull(), inside, peaks[i], diag.threshold),
            )
        }
        val hits = reps.count { it.recognised }

        val usable = chosen != null && hits >= MIN_REPS && hits * 10 >= counted * 6
        val params = if (usable) chosen else null
        val headline = when {
            params != null && hits == counted && extras == 0 -> "All $counted gestures recognised"
            params != null -> "$hits of $counted gestures recognised"
            else -> "No setting recognised your gesture reliably"
        }

        val warnings = ArrayList<String>()
        if (params != null) {
            // Rough check against simulated running; real running differs, so this only warns.
            val softest = levels.minOf { it.threshold }
            val p = params.copy(threshold = softest)
            when {
                simulatedRunTriggers(p, 11f) > 0 ->
                    warnings += "At the Light or Balanced level this may also trigger while running or on a bumpy ride. Keep pocket guard on."
                simulatedRunTriggers(p, 16f) > 0 ->
                    warnings += "Light may trigger during hard running. Balanced or Firm are safer if you run with the phone in hand."
            }
        }
        if (extras > 0) {
            warnings += "It also triggered $extras time${if (extras == 1) "" else "s"} outside your gestures."
        }
        if (params == null && hits < counted) warnings += failureHint(reps)
        warnings += baseWarnings

        return CalResult(
            params, reps, hits, counted, extras, lo, hi, headline, warnings, rate,
            averagePeak = average,
            averageStrength = strength,
            levels = if (params != null) levels else emptyList(),
        )
    }

    /** [wanted] kept inside the working range [lo]..[hi], snapped to 0.5 where possible. */
    internal fun pickThreshold(lo: Float, hi: Float, wanted: Float): Float {
        var t = wanted.coerceIn(lo, hi)
        val snapped = (t * 2f).roundToInt() / 2f
        t = if (snapped in lo..hi) snapped else (t / T_STEP).roundToInt() * T_STEP
        if (t > SLIDER_MAX && lo <= SLIDER_MAX) t = SLIDER_MAX
        if (t < SLIDER_MIN && hi >= SLIDER_MIN) t = SLIDER_MIN
        return t
    }

    fun report(
        r: CalResult,
        device: String,
        sensor: String,
        previous: DetectorParams,
        level: Level = Level.BALANCED,
    ): String = buildString {
        appendLine("Chop Torch calibration report")
        appendLine("Device: $device")
        appendLine("Sensor: $sensor")
        appendLine(String.format(Locale.US, "Sample rate: %.0f Hz", r.sampleRateHz))
        appendLine("Before: ${previous.summary()}")
        appendLine("Result: ${r.headline}")
        if (!r.averagePeak.isNaN()) appendLine(String.format(Locale.US, "Average peak force: %.1f m/s²", r.averagePeak))
        if (!r.averageStrength.isNaN()) appendLine(String.format(Locale.US, "Average strength (highest passing trigger): %.2f m/s²", r.averageStrength))
        r.paramsFor(level)?.let { appendLine("Chosen (${level.label}): ${it.summary()}") }
        if (r.levels.isNotEmpty()) {
            appendLine(Level.entries.joinToString(", ", "Levels: ") { String.format(Locale.US, "%s %.2f", it.label, r.levels[it.ordinal].threshold) })
        }
        if (!r.rangeLo.isNaN()) {
            appendLine(String.format(Locale.US, "Working trigger range: %.2f to %.2f m/s²", r.rangeLo, r.rangeHi))
        }
        r.reps.forEach {
            appendLine(String.format(Locale.US, "Gesture %d: peak %.1f m/s², %s", it.number, it.peak, it.detail))
        }
        r.warnings.forEach { appendLine("Note: $it") }
    }

    private fun Config.toParams(t: Float) =
        DetectorParams(threshold = t, requireSettle = requireSettle, maxGapMs = maxGapMs, maxSwingMs = maxSwingMs)

    private fun score(
        rec: Recording,
        bursts: List<BurstSegmenter.Burst>,
        ignored: BooleanArray,
        p: DetectorParams,
    ): Score {
        val fires = ArrayList<Long>(8)
        var nowMs = 0L
        val det = ChopDetector(p, onDoubleChop = { fires += nowMs })
        for (i in 0 until rec.size) {
            nowMs = rec.ts[i] / 1_000_000L
            det.onSample(rec.x[i], rec.y[i], rec.z[i], rec.ts[i])
        }
        val hit = BooleanArray(bursts.size)
        var extras = 0
        for (f in fires) {
            val bi = bursts.indexOfFirst { f >= it.startMs - WIN_BEFORE_MS && f <= it.endMs + WIN_AFTER_MS }
            when {
                bi < 0 -> extras++
                ignored[bi] -> Unit
                hit[bi] -> extras++
                else -> hit[bi] = true
            }
        }
        return Score(hit, extras)
    }

    /** Triggers in 30 s of synthetic running with [ampY] m/s² vertical bounce at 2.8 steps/s. */
    private fun simulatedRunTriggers(p: DetectorParams, ampY: Float): Int {
        var fired = 0
        val det = ChopDetector(p, onDoubleChop = { fired++ })
        val hz = 2.8
        for (i in 0 until 3000) {
            val t = i / 100.0
            val x = (ampY * 0.45 * kotlin.math.sin(Math.PI * hz * t)).toFloat()
            val y = 9.81f + (ampY * kotlin.math.sin(2 * Math.PI * hz * t)).toFloat()
            det.onSample(x, y, 0f, i * 10_000_000L)
        }
        return fired
    }

    private fun replayEvents(rec: Recording, p: DetectorParams): List<ChopDetector.Event> {
        val events = ArrayList<ChopDetector.Event>()
        val det = ChopDetector(p, onDoubleChop = {}, onEvent = { events += it })
        for (i in 0 until rec.size) det.onSample(rec.x[i], rec.y[i], rec.z[i], rec.ts[i])
        return events
    }

    /** Peak force of each gesture, measured exactly like the app's "Live force" readout. */
    private fun burstPeaks(rec: Recording, bursts: List<BurstSegmenter.Burst>): FloatArray {
        val peaks = FloatArray(bursts.size)
        val meter = ChopDetector(DetectorParams(threshold = Float.MAX_VALUE), onDoubleChop = {})
        var b = 0
        for (i in 0 until rec.size) {
            meter.onSample(rec.x[i], rec.y[i], rec.z[i], rec.ts[i])
            val t = rec.ts[i] / 1_000_000L
            while (b < bursts.size && t > bursts[b].endMs + WIN_AFTER_MS) b++
            if (b < bursts.size && t >= bursts[b].startMs - WIN_BEFORE_MS) peaks[b] = max(peaks[b], meter.lastMag)
        }
        return peaks
    }

    private fun explain(
        ignored: Boolean,
        fire: ChopDetector.Event?,
        inside: List<ChopDetector.Event>,
        peak: Float,
        threshold: Float,
    ): String = when {
        ignored -> "Ignored: much weaker than your other gestures"
        fire != null -> "Recognised · ${fire.gapMs} ms between chops"
        inside.isEmpty() && peak <= threshold ->
            String.format(Locale.US, "Too gentle: peak %.1f, trigger is %.1f", peak, threshold)
        inside.isEmpty() -> "Too soon after the previous gesture"
        else -> when (inside.last().kind) {
            ChopDetector.Kind.NO_STOP -> "Swing had no sharp stop"
            ChopDetector.Kind.NO_SETTLE -> "Phone kept moving between the two chops"
            ChopDetector.Kind.GAP_OUT -> "The two chops were too close together"
            else -> "Only one chop detected"
        }
    }

    private fun failureHint(reps: List<RepResult>): String {
        val missed = reps.filter { !it.recognised && !it.ignored }.map { it.detail }
        return when {
            missed.count { it.startsWith("Only one chop") } * 2 >= missed.size ->
                "Most gestures looked like a single chop. Do two distinct chops, about a third of a second apart."
            missed.count { it.startsWith("Swing had no sharp stop") } * 2 >= missed.size ->
                "Stop each chop sharply, as if hitting the edge of a table."
            missed.count { it.startsWith("Too gentle") } * 2 >= missed.size ->
                "The gestures were very gentle. Chop a bit harder."
            else -> "Try again, keeping every gesture the same."
        }
    }

    private fun sensorWarnings(rec: Recording, rate: Float, maxRange: Float): List<String> {
        val w = ArrayList<String>()
        if (maxRange > 0f && rec.maxAbsComponent() >= maxRange * 0.97f) {
            w += String.format(
                Locale.US,
                "The sensor hit its limit of %.0f m/s², so your hardest movements all read the same. Detection still works.",
                maxRange,
            )
        }
        if (rec.size > 10 && rate < 60f) {
            w += String.format(Locale.US, "The sensor sent only %.0f readings per second. Very quick chops may be missed.", rate)
        }
        return w
    }
}

/** Everything one calibration run produced. */
class TrainingOutcome(
    val cal: CalResult,
    val model: dev.incog.choptorch.ml.PersonalModel?,
    /** Classic detector triggers in the normal-use recording, per [Level]; null without that recording. */
    val classicFalseTriggers: List<Int>?,
    val normalSeconds: Float,
)

/**
 * One calibration run: counts gestures live, then records everyday movement (no gestures),
 * then analyses both.
 */
class CalibrationSession(
    val target: Int = 8,
    private val armDelayMs: Long = 1500L,
    private val timeoutMs: Long = 120_000L,
    private val normalMs: Long = 30_000L,
) {
    enum class Signal { NONE, ARMED, REP, DONE }
    enum class Stage { GESTURES, NORMAL }

    val recording = Recording()
    val normal = Recording()
    private val segmenter = BurstSegmenter()
    private val meter = ChopDetector(DetectorParams(threshold = Float.MAX_VALUE), onDoubleChop = {})
    private var startMs = -1L
    private var normalStartMs = -1L

    @Volatile var stage = Stage.GESTURES
        private set
    var armed = false
        private set
    @Volatile var finished = false
        private set
    /** Milliseconds of normal use recorded so far. */
    @Volatile var normalElapsedMs = 0L
        private set

    val reps: Int get() = segmenter.bursts.size
    val bursts: List<BurstSegmenter.Burst> get() = segmenter.bursts

    /** True from the first movement of a gesture until it has been still long enough to count. */
    @Volatile var inGesture = false
        private set

    fun takePeak(): Float = meter.takePeak()

    /** Call on the sensor thread. */
    fun startNormal() {
        segmenter.flush()
        inGesture = false
        stage = Stage.NORMAL
    }

    fun feed(ax: Float, ay: Float, az: Float, tsNs: Long): Signal {
        if (finished) return Signal.NONE
        val now = tsNs / 1_000_000L
        if (startMs < 0) startMs = now
        meter.onSample(ax, ay, az, tsNs)
        if (stage == Stage.NORMAL) {
            if (normalStartMs < 0) normalStartMs = now
            normal.add(ax, ay, az, tsNs)
            normalElapsedMs = now - normalStartMs
            return if (normalElapsedMs >= normalMs) Signal.DONE else Signal.NONE
        }
        if (!armed) {
            // Ignore the first moments so moving into position is not recorded.
            if (now - startMs >= armDelayMs) {
                armed = true
                return Signal.ARMED
            }
            return Signal.NONE
        }
        recording.add(ax, ay, az, tsNs)
        val closed = segmenter.onSample(ax, ay, az, tsNs)
        inGesture = segmenter.isActive
        if (closed) return if (reps >= target) Signal.DONE else Signal.REP
        if (now - startMs >= timeoutMs) return Signal.DONE
        return Signal.NONE
    }

    fun analyze(sensorMaxRange: Float): CalResult {
        finished = true
        segmenter.flush()
        return Calibrator.analyze(recording, segmenter.bursts, sensorMaxRange)
    }

    /** Classic calibration plus the personal model. Call on the sensor thread after recording stops. */
    fun analyzeAll(sensorMaxRange: Float): TrainingOutcome {
        val cal = analyze(sensorMaxRange)
        val normalRec = normal.takeIf { it.durationMs >= MIN_NORMAL_MS }
        val model = dev.incog.choptorch.ml.Trainer.train(
            recording, segmenter.bursts, cal.reps.map { it.ignored }, cal.reps.map { it.peak }, normalRec,
        )
        val classicFalse = if (normalRec != null && cal.levels.isNotEmpty()) {
            cal.levels.map { p ->
                var n = 0
                val det = ChopDetector(p, onDoubleChop = { n++ })
                for (i in 0 until normalRec.size) det.onSample(normalRec.x[i], normalRec.y[i], normalRec.z[i], normalRec.ts[i])
                n
            }
        } else null
        return TrainingOutcome(cal, model, classicFalse, (normalRec?.durationMs ?: 0L) / 1000f)
    }

    companion object {
        const val MIN_NORMAL_MS = 5_000L
    }
}
