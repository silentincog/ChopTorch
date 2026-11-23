package com.kiro.choptorch.ml

import com.kiro.choptorch.GestureEngine
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** How close a movement must be to your samples. */
enum class Strictness(val label: String, val hint: String) {
    RELAXED("Relaxed", "Accepts looser versions of your gesture"),
    BALANCED("Balanced", "Close to your samples, ignores everyday movement"),
    STRICT("Strict", "Only very close copies of your gesture"),
}

/** Where a sample came from. Learned samples can be undone; calibration samples are kept. */
enum class Origin(val learned: Boolean, val reason: String) {
    CALIBRATION(false, "Calibration"),
    NORMAL_USE(false, "Normal-use recording"),
    TAUGHT(true, "You corrected it in Test & teach"),
    AUTO_RETRY(true, "You repeated the gesture right after a near miss"),
    AUTO_MISS(true, "You switched the torch by hand right after a near miss"),
    AUTO_CONFIRMED(true, "A borderline match you kept"),
    AUTO_UNDO(true, "You switched the torch back right away"),
    NOTIFICATION(true, "You tapped Wasn't me"),
    // New values go at the end: the ordinal is stored in the model file.
    AUTO_REPEAT(true, "You tried the same movement twice, then used the torch tile"),
}

/** What the learner may ask the current model before trusting a new kind of sample. */
interface Judge {
    /** Shape distance between two windows, on the same scale as the cutoffs. */
    fun pairDistance(a: FloatArray, b: FloatArray): Float
    /** Distance from a window to the closest known non-gesture. */
    fun nearestNegative(w: FloatArray): Float
    /** The loosest cutoff (Relaxed). */
    val looseCutoff: Float
    /** Weaker movements than this are not compared (m/s²). */
    val minPeak: Float
    /**
     * Same structure as your gesture: as many separate bursts of motion, and a similar length.
     * Stops a repeated single chop being learned as a double chop, for example.
     */
    fun plausible(w: FloatArray): Boolean
}

/** Number of separate bursts (frames above 40 % of the window's peak, split by 40 ms or more of calm) and their total span. */
internal fun burstShape(w: FloatArray, frames: Int): Pair<Int, Int> {
    val mag = FloatArray(frames) { i ->
        val x = w[i * 3]; val y = w[i * 3 + 1]; val z = w[i * 3 + 2]
        kotlin.math.sqrt(x * x + y * y + z * z)
    }
    val level = (mag.maxOrNull() ?: 0f) * 0.4f
    var bursts = 0; var first = -1; var last = -1; var calm = 2
    for (i in 0 until frames) {
        if (mag[i] >= level) {
            if (calm >= 2) bursts++
            calm = 0
            if (first < 0) first = i
            last = i
        } else calm++
    }
    return bursts to (if (first < 0) 0 else last - first + 1)
}

/** One labelled movement window. */
class Sample(val id: Long, val data: FloatArray, val origin: Origin, val timeMs: Long) {
    fun copy() = Sample(id, data.copyOf(), origin, timeMs)
}

/** How well the model did on the data it was trained with. */
data class ModelStats(
    /** Leave-one-out: each gesture checked against the other samples only. */
    val recognised: IntArray,          // per strictness
    val falseTriggers: IntArray,       // per strictness, in the user's normal-use recording
    val positives: Int,
    val normalWindows: Int,
    val normalSeconds: Float,
)

/**
 * The personal gesture model: your own gesture samples as templates, a set of "not the gesture"
 * samples, and a distance cutoff per [Strictness] learned from both.
 *
 * A movement matches when the average DTW distance to its two closest templates is below the
 * cutoff. It learns incrementally: [addPositive] / [addNegative] add one labelled sample and
 * recompute the cutoffs in milliseconds, on the phone. Calibration samples are protected, so
 * learning adapts the model without letting it drift away from what you taught it.
 */
class PersonalModel(
    val windowFrames: Int,
    val onsetLevel: Float,
    override var minPeak: Float,
    val positives: MutableList<Sample>,
    /** Non-gestures: the normal-use recording, calibration handling, and learned corrections. */
    val negatives: MutableList<Sample>,
    /** Synthetic walking/running windows; used for cutoffs, not reported as user false triggers. */
    val synthetic: MutableList<FloatArray>,
    var strictness: Strictness = Strictness.BALANCED,
    var normalSeconds: Float = 0f,
    var nextId: Long = 1L,
) : Judge {
    companion object {
        const val MAX_POSITIVES = 24
        const val MAX_NEGATIVES = 400
        private const val MAGIC_V1 = 0x43544D31 // "CTM1"
        private const val MAGIC_V2 = 0x43544D32 // "CTM2"
        private const val K = 2

        /** 0..100, where 50 is exactly the cutoff: above 50 triggers. */
        fun matchPercent(score: Float, threshold: Float): Int =
            if (threshold <= 0f) 0 else (100f * (1f - score / (2f * threshold))).toInt().coerceIn(0, 100)

        fun load(file: File): PersonalModel? = runCatching {
            DataInputStream(file.inputStream().buffered()).use { s ->
                when (s.readInt()) {
                    MAGIC_V2 -> readV2(s)
                    MAGIC_V1 -> readV1(s)
                    else -> error("not a model file")
                }.also { it.retrain() }
            }
        }.getOrNull()

        private fun readV2(s: DataInputStream): PersonalModel {
            val frames = s.readInt()
            val onset = s.readFloat()
            val minPeak = s.readFloat()
            val strictness = Strictness.entries[s.readInt()]
            val normalSeconds = s.readFloat()
            val nextId = s.readLong()
            fun readSamples(): MutableList<Sample> = MutableList(s.readInt()) {
                val id = s.readLong()
                val origin = Origin.entries[s.readInt()]
                val time = s.readLong()
                Sample(id, FloatArray(frames * 3) { s.readFloat() }, origin, time)
            }
            val pos = readSamples()
            val neg = readSamples()
            val syn = MutableList(s.readInt()) { FloatArray(frames * 3) { s.readFloat() } }
            return PersonalModel(frames, onset, minPeak, pos, neg, syn, strictness, normalSeconds, nextId)
        }

        /** Version 3.0 files: no per-sample metadata. */
        private fun readV1(s: DataInputStream): PersonalModel {
            val frames = s.readInt()
            val onset = s.readFloat()
            val minPeak = s.readFloat()
            val strictness = Strictness.entries[s.readInt()]
            val normalSeconds = s.readFloat()
            val normalWindows = s.readInt()
            var id = 1L
            fun readList(): List<FloatArray> = List(s.readInt()) { FloatArray(frames * 3) { s.readFloat() } }
            val pos = readList().map { Sample(id++, it, Origin.CALIBRATION, 0L) }.toMutableList()
            val neg = readList().mapIndexed { i, w ->
                Sample(id++, w, if (i < normalWindows) Origin.NORMAL_USE else Origin.CALIBRATION, 0L)
            }.toMutableList()
            val syn = readList().toMutableList()
            return PersonalModel(frames, onset, minPeak, pos, neg, syn, strictness, normalSeconds, id)
        }
    }

    val band: Int get() = max(3, windowFrames / 4)

    /** Shape-only copies of the templates: each scaled to unit RMS so force doesn't affect the match. */
    private var normPositives: List<FloatArray> = emptyList()

    private fun normalize(w: FloatArray): FloatArray {
        var sum = 0f
        for (v in w) sum += v * v
        val rms = kotlin.math.sqrt(sum / (w.size / 3)).coerceAtLeast(0.5f)
        return FloatArray(w.size) { w[it] / rms }
    }

    var thresholds = FloatArray(3)
        internal set
    var stats = ModelStats(IntArray(3), IntArray(3), 0, 0, 0f)
        internal set

    val threshold: Float get() = thresholds[strictness.ordinal]
    val normalWindows: Int get() = negatives.count { it.origin == Origin.NORMAL_USE }
    val learnedPositives: Int get() = positives.count { it.origin.learned }
    val learnedNegatives: Int get() = negatives.count { it.origin.learned }

    override fun pairDistance(a: FloatArray, b: FloatArray): Float =
        Dtw.distance(normalize(a), normalize(b), windowFrames, band)

    override fun nearestNegative(w: FloatArray): Float {
        val nw = normalize(w)
        var best = Float.MAX_VALUE
        for (n in negatives) best = min(best, Dtw.distance(nw, normalize(n.data), windowFrames, band))
        for (n in synthetic) best = min(best, Dtw.distance(nw, normalize(n), windowFrames, band))
        return best
    }

    override val looseCutoff: Float get() = thresholds[0]

    private var typicalBursts = 0
    private var typicalSpan = 0f

    override fun plausible(w: FloatArray): Boolean {
        if (typicalSpan <= 0f) return true
        val (b, span) = burstShape(w, windowFrames)
        return b == typicalBursts && span.toFloat() in (typicalSpan * 0.4f)..(typicalSpan * 2.5f)
    }

    /** Average distance to the [K] closest templates, skipping template [exclude]. */
    fun score(w: FloatArray, exclude: Int = -1): Float {
        if (normPositives.size != positives.size) normPositives = positives.map { normalize(it.data) }
        val nw = normalize(w)
        var best1 = Float.MAX_VALUE
        var best2 = Float.MAX_VALUE
        normPositives.forEachIndexed { i, t ->
            if (i == exclude) return@forEachIndexed
            val d = Dtw.distance(nw, t, windowFrames, band)
            if (d < best1) { best2 = best1; best1 = d } else if (d < best2) best2 = d
        }
        if (best1 == Float.MAX_VALUE) return Float.MAX_VALUE
        return if (best2 == Float.MAX_VALUE || positives.size - (if (exclude >= 0) 1 else 0) < K) best1 else (best1 + best2) / 2f
    }

    /** Recomputes the cutoffs from the current samples. */
    fun retrain() {
        normPositives = positives.map { normalize(it.data) }
        // Typical structure, from calibration samples when there are enough of them.
        val ref = positives.filter { it.origin == Origin.CALIBRATION }.takeIf { it.size >= 3 } ?: positives
        val shapes = ref.map { burstShape(it.data, windowFrames) }
        typicalBursts = shapes.groupingBy { it.first }.eachCount().maxByOrNull { it.value }?.key ?: 0
        typicalSpan = shapes.map { it.second }.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2].toFloat() }
        val pos = positives.indices.map { score(positives[it].data, exclude = it) }.sorted()
        val userNeg = negatives.map { score(it.data) }
        val allNeg = (userNeg + synthetic.map { score(it) }).sorted()
        val posMax = pos.lastOrNull() ?: 1f
        val negMin = allNeg.firstOrNull() ?: Float.MAX_VALUE

        thresholds = if (posMax < negMin) {
            // Separable. Place the cutoffs in the gap between your farthest sample and the closest
            // non-gesture. If nothing non-gesture is near, assume one at 3x your spread (at least
            // 0.5), because a handful of very similar samples understates how much real ones vary.
            val ceiling = min(negMin, max(posMax * 3f, 0.5f))
            val gap = (ceiling - posMax).coerceAtLeast(0f)
            floatArrayOf(posMax + gap * 0.7f, posMax + gap * 0.5f, posMax + gap * 0.25f)
        } else {
            // Overlap: trade recognised gestures against false triggers with different weights.
            floatArrayOf(bestCut(pos, allNeg, 1.5f), bestCut(pos, allNeg, 3f), bestCut(pos, allNeg, 6f))
        }
        // Keep Relaxed >= Balanced >= Strict.
        thresholds[1] = min(thresholds[1], thresholds[0])
        thresholds[2] = min(thresholds[2], thresholds[1])

        val normal = negatives.indices.filter { negatives[it].origin == Origin.NORMAL_USE }.map { userNeg[it] }
        stats = ModelStats(
            recognised = IntArray(3) { s -> pos.count { it <= thresholds[s] } },
            falseTriggers = IntArray(3) { s -> normal.count { it <= thresholds[s] } },
            positives = positives.size,
            normalWindows = normal.size,
            normalSeconds = normalSeconds,
        )
    }

    private fun bestCut(pos: List<Float>, neg: List<Float>, fpWeight: Float): Float {
        val candidates = (pos + neg).distinct().sorted()
        var bestT = pos.firstOrNull() ?: 0f
        var bestScore = Float.NEGATIVE_INFINITY
        for (t in candidates) {
            val tp = pos.count { it <= t }
            val fp = neg.count { it <= t }
            val sc = tp - fpWeight * fp
            if (sc > bestScore) { bestScore = sc; bestT = t }
        }
        // Halfway to the next non-gesture above the cut, so the cut is not exactly on a sample.
        val nextNeg = neg.firstOrNull { it > bestT } ?: (bestT * 1.2f)
        return (bestT + nextNeg) / 2f
    }

    /** Adds one of your gestures and retrains. @return the new sample's id */
    fun addPositive(w: FloatArray, peak: Float, origin: Origin = Origin.TAUGHT, timeMs: Long = System.currentTimeMillis()): Long {
        val id = nextId++
        positives += Sample(id, w, origin, timeMs)
        evict(positives, MAX_POSITIVES, setOf(Origin.CALIBRATION))
        normPositives = emptyList()
        minPeak = min(minPeak, peak * 0.5f)
        retrain()
        return id
    }

    /** Adds one non-gesture and retrains. @return the new sample's id */
    fun addNegative(w: FloatArray, origin: Origin = Origin.TAUGHT, timeMs: Long = System.currentTimeMillis()): Long {
        val id = nextId++
        negatives += Sample(id, w, origin, timeMs)
        evict(negatives, MAX_NEGATIVES, setOf(Origin.CALIBRATION, Origin.NORMAL_USE))
        retrain()
        return id
    }

    /** Oldest unprotected samples go first, so learning follows how your gesture changes over time. */
    private fun evict(list: MutableList<Sample>, max: Int, protect: Set<Origin>) {
        while (list.size > max) {
            val i = list.indexOfFirst { it.origin !in protect }
            list.removeAt(if (i >= 0) i else 0)
        }
    }

    /** Removes one sample (undo). @return true if it existed */
    fun removeSample(id: Long): Boolean {
        val removed = positives.removeAll { it.id == id } or negatives.removeAll { it.id == id }
        if (removed) {
            normPositives = emptyList()
            retrain()
        }
        return removed
    }

    /** Drops everything learned after calibration. */
    fun forgetLearned() {
        positives.removeAll { it.origin.learned }
        negatives.removeAll { it.origin.learned }
        normPositives = emptyList()
        retrain()
    }

    fun save(file: File) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { s ->
            s.writeInt(MAGIC_V2)
            s.writeInt(windowFrames)
            s.writeFloat(onsetLevel)
            s.writeFloat(minPeak)
            s.writeInt(strictness.ordinal)
            s.writeFloat(normalSeconds)
            s.writeLong(nextId)
            for (list in listOf(positives, negatives)) {
                s.writeInt(list.size)
                list.forEach { smp ->
                    s.writeLong(smp.id)
                    s.writeInt(smp.origin.ordinal)
                    s.writeLong(smp.timeMs)
                    smp.data.forEach(s::writeFloat)
                }
            }
            s.writeInt(synthetic.size)
            synthetic.forEach { w -> w.forEach(s::writeFloat) }
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    fun summary(): String = String.format(
        Locale.US,
        "model: %d gestures (%d learned), %d non-gestures (%d learned), window %d ms, onset %.1f m/s², %s cutoff %.2f (relaxed %.2f, strict %.2f)",
        positives.size, learnedPositives, negatives.size, learnedNegatives, windowFrames * Frontend.FRAME_MS, onsetLevel,
        strictness.label.lowercase(), threshold, thresholds[0], thresholds[2],
    )

    /** Deep copy. Pass retrain = false when you are about to change it and retrain anyway. */
    fun copy(retrain: Boolean = true): PersonalModel = PersonalModel(
        windowFrames, onsetLevel, minPeak,
        positives.map { it.copy() }.toMutableList(),
        negatives.map { it.copy() }.toMutableList(),
        synthetic.map { it.copyOf() }.toMutableList(),
        strictness, normalSeconds, nextId,
    ).also { c ->
        if (retrain) c.retrain() else { c.thresholds = thresholds.copyOf(); c.stats = stats }
    }
}

/** Live engine: runs the [Frontend] and asks the model about every movement window. */
class PersonalDetector(
    @Volatile var model: PersonalModel,
    private val onTrigger: () -> Unit,
    /** Also report movements below the model's minimum force (Test & teach uses this). */
    private val reportGentle: Boolean = false,
    private val onResult: ((Result) -> Unit)? = null,
) : GestureEngine {
    class Result(
        val score: Float,
        val threshold: Float,
        val matched: Boolean,
        val peak: Float,
        val window: FloatArray,
        val onsetMs: Long,
        /** Weaker than the model's minimum force, so it was not compared at all. */
        val gentle: Boolean = false,
    ) {
        val percent: Int get() = if (gentle) 0 else PersonalModel.matchPercent(score, threshold)
    }

    companion object {
        const val COOLDOWN_MS = 1200L
    }

    private val frontend = Frontend(model.onsetLevel, model.windowFrames, ::evaluate)
    private var lastFireMs = Long.MIN_VALUE / 2

    /** True if [m] can replace the current model without restarting the signal pipeline. */
    fun compatible(m: PersonalModel): Boolean =
        m.windowFrames == model.windowFrames && m.onsetLevel == model.onsetLevel

    override fun onSample(ax: Float, ay: Float, az: Float, tsNs: Long) = frontend.onSample(ax, ay, az, tsNs)

    override fun takePeak(): Float = frontend.takePeak()

    override fun restart() = frontend.reset()

    @Volatile override var cooldownMs: Long = COOLDOWN_MS

    private fun evaluate(w: Window) {
        val m = model
        if (w.onsetMs - lastFireMs < cooldownMs) return
        if (w.peak < m.minPeak) {
            if (reportGentle) onResult?.invoke(Result(Float.MAX_VALUE, m.threshold, false, w.peak, w.data, w.onsetMs, gentle = true))
            return
        }
        val s = m.score(w.data)
        val thr = m.threshold
        val matched = s <= thr
        if (matched) lastFireMs = w.endMs
        onResult?.invoke(Result(s, thr, matched, w.peak, w.data, w.onsetMs))
        if (matched) onTrigger()
    }
}
