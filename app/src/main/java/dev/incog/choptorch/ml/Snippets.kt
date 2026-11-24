package dev.incog.choptorch.ml

import dev.incog.choptorch.calib.BurstSegmenter
import dev.incog.choptorch.calib.Recording
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.roundToInt

/** A few seconds of raw accelerometer data, optionally with where the gesture is inside it. */
class Snippet(
    val ts: LongArray,
    val x: FloatArray,
    val y: FloatArray,
    val z: FloatArray,
    val gestureStartNs: Long = 0L,
    val gestureEndNs: Long = 0L,
    val force: Float = 0f,
    /** Confirmed by a clear action (repeating it, or using the torch tile), not just by leaving the torch on. */
    val strong: Boolean = false,
) {
    val size: Int get() = ts.size
    val durationMs: Long get() = if (size < 2) 0L else (ts[size - 1] - ts[0]) / 1_000_000L
}

/** The last few seconds of raw samples, so a gesture can be kept once we know it was one. */
class RawRing(private val capacity: Int = 1000) {
    private val ts = LongArray(capacity)
    private val x = FloatArray(capacity)
    private val y = FloatArray(capacity)
    private val z = FloatArray(capacity)
    private var count = 0L

    fun add(ax: Float, ay: Float, az: Float, tsNs: Long) {
        val i = (count % capacity).toInt()
        ts[i] = tsNs; x[i] = ax; y[i] = ay; z[i] = az
        count++
    }

    /** Samples with timestamps in [fromNs]..[toNs] that are still in the buffer. */
    fun slice(fromNs: Long, toNs: Long, gestureStartNs: Long = 0L, gestureEndNs: Long = 0L, force: Float = 0f, strong: Boolean = false): Snippet {
        val first = maxOf(0L, count - capacity)
        var n = 0
        for (k in first until count) if (ts[(k % capacity).toInt()] in fromNs..toNs) n++
        val ots = LongArray(n); val ox = FloatArray(n); val oy = FloatArray(n); val oz = FloatArray(n)
        var j = 0
        for (k in first until count) {
            val i = (k % capacity).toInt()
            if (ts[i] in fromNs..toNs) { ots[j] = ts[i]; ox[j] = x[i]; oy[j] = y[i]; oz[j] = z[i]; j++ }
        }
        return Snippet(ots, ox, oy, oz, gestureStartNs, gestureEndNs, force, strong)
    }
}

/** Snippets collected in Auto mode, kept until the personal model is built. */
object SnippetStore {
    const val MAX_GESTURES = 20
    const val MAX_EVERYDAY = 20
    const val MAX_FALSE = 10
    private const val MAGIC = 0x43545332 // "CTS2"

    class Contents(val gestures: List<Snippet>, val everyday: List<Snippet>, val falseTriggers: List<Snippet>)

    private fun file(dir: File) = File(dir, "auto_snippets.bin")

    fun load(dir: File): Contents = runCatching {
        DataInputStream(file(dir).inputStream().buffered()).use { s ->
            require(s.readInt() == MAGIC)
            fun list() = List(s.readInt()) {
                val force = s.readFloat()
                val strong = s.readBoolean()
                val gs = s.readLong()
                val ge = s.readLong()
                val n = s.readInt()
                val ts = LongArray(n); val x = FloatArray(n); val y = FloatArray(n); val z = FloatArray(n)
                for (i in 0 until n) { ts[i] = s.readLong(); x[i] = s.readFloat(); y[i] = s.readFloat(); z[i] = s.readFloat() }
                Snippet(ts, x, y, z, gs, ge, force, strong)
            }
            Contents(list(), list(), list())
        }
    }.getOrElse { Contents(emptyList(), emptyList(), emptyList()) }

    /** @return the number of gestures stored afterwards */
    fun addGesture(dir: File, s: Snippet): Int {
        val c = load(dir)
        val g = (c.gestures + s).takeLast(MAX_GESTURES)
        save(dir, Contents(g, c.everyday, c.falseTriggers))
        return g.size
    }

    fun addEveryday(dir: File, s: Snippet) {
        val c = load(dir)
        save(dir, Contents(c.gestures, (c.everyday + s).takeLast(MAX_EVERYDAY), c.falseTriggers))
    }

    fun addFalse(dir: File, s: Snippet) {
        val c = load(dir)
        save(dir, Contents(c.gestures, c.everyday, (c.falseTriggers + s).takeLast(MAX_FALSE)))
    }

    fun clear(dir: File) {
        file(dir).delete()
    }

    private fun save(dir: File, c: Contents) {
        val tmp = File(dir, "auto_snippets.bin.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { s ->
            s.writeInt(MAGIC)
            for (list in listOf(c.gestures, c.everyday, c.falseTriggers)) {
                s.writeInt(list.size)
                for (sn in list) {
                    s.writeFloat(sn.force); s.writeBoolean(sn.strong); s.writeLong(sn.gestureStartNs); s.writeLong(sn.gestureEndNs)
                    s.writeInt(sn.size)
                    for (i in 0 until sn.size) { s.writeLong(sn.ts[i]); s.writeFloat(sn.x[i]); s.writeFloat(sn.y[i]); s.writeFloat(sn.z[i]) }
                }
            }
        }
        if (!tmp.renameTo(file(dir))) { file(dir).delete(); tmp.renameTo(file(dir)) }
    }
}

/**
 * Trains a personal model from snippets collected in everyday use, with the same trainer as
 * calibration. Not every "gesture" Auto collected is really one (a bump that switched the torch
 * and nobody noticed counts too), so it first keeps only the gestures that look alike: the ones
 * close in shape to the most typical gesture, preferring clearly confirmed ones.
 */
object AutoModelBuilder {
    const val MIN_GESTURES = 8
    private const val STEP_NS = 10_000_000L

    class Result(val model: PersonalModel?, val used: Int, val outliers: Int, val reason: String)

    fun build(gestures: List<Snippet>, everyday: List<Snippet>, falseTriggers: List<Snippet> = emptyList()): Result {
        val usable = gestures.filter { it.size >= 10 && it.gestureEndNs > it.gestureStartNs }
        if (usable.size < MIN_GESTURES) return Result(null, 0, 0, "${usable.size} of $MIN_GESTURES gestures")
        val consistent = consistent(usable)
        val outliers = usable.size - consistent.size
        if (consistent.size < MIN_GESTURES) {
            return Result(null, consistent.size, outliers, "only ${consistent.size} gestures look alike so far")
        }

        val rec = Recording()
        val bursts = ArrayList<BurstSegmenter.Burst>()
        var t = 0L
        for (g in consistent) {
            val off = append(rec, g, t)
            bursts += BurstSegmenter.Burst((g.gestureStartNs + off) / 1_000_000L, (g.gestureEndNs + off) / 1_000_000L)
            t = rec.ts[rec.size - 1] + STEP_NS
        }
        // Everyday clips and switched-back false triggers both teach it what to ignore.
        val normal = Recording()
        var tn = 0L
        for (e in everyday + falseTriggers) {
            if (e.size < 10) continue
            append(normal, e, tn)
            tn = normal.ts[normal.size - 1] + STEP_NS
        }
        val model = Trainer.train(
            rec, bursts, List(bursts.size) { false }, consistent.map { it.force },
            normal.takeIf { it.durationMs >= 3_000L },
        ) ?: return Result(null, consistent.size, outliers, "could not cut gesture windows")
        // Only switch to a model that recognises nearly all of the gestures it was given.
        val s = model.stats
        val b = Strictness.BALANCED.ordinal
        return if (s.recognised[b] >= s.positives - 1 && s.falseTriggers[b] == 0) {
            Result(model, consistent.size, outliers, "ok")
        } else {
            Result(null, consistent.size, outliers, "recognised ${s.recognised[b]}/${s.positives}, ${s.falseTriggers[b]} false")
        }
    }

    /** The gestures close in shape to the most typical one (medoid). */
    private fun consistent(gs: List<Snippet>): List<Snippet> {
        val forces = gs.map { it.force }.sorted()
        val onset = (forces[forces.size / 2] * 0.35f).coerceIn(4f, 12f)
        val durs = gs.map { ((it.gestureEndNs - it.gestureStartNs) / 1_000_000L).toFloat() }.sorted()
        val frames = ((durs[((durs.size - 1) * 0.9f).roundToInt()] + 160f) / Frontend.FRAME_MS).roundToInt().coerceIn(15, 80)
        val windows = gs.map { g -> firstWindow(g, onset, frames) }
        val idx = windows.indices.filter { windows[it] != null }
        if (idx.size < 3) return gs
        val d = Array(idx.size) { FloatArray(idx.size) }
        for (a in idx.indices) for (b in a + 1 until idx.size) {
            val v = Shape.distance(windows[idx[a]]!!, windows[idx[b]]!!, frames)
            d[a][b] = v; d[b][a] = v
        }
        fun medianTo(a: Int) = d[a].filterIndexed { b, _ -> b != a }.sorted().let { it[it.size / 2] }
        // Prefer a medoid among clearly confirmed gestures if there are enough of them.
        val strong = idx.indices.filter { gs[idx[it]].strong }
        val pool = if (strong.size >= 3) strong else idx.indices.toList()
        val medoid = pool.minByOrNull(::medianTo)!!
        val radius = medianTo(medoid) * 2f
        return idx.indices.filter { it == medoid || d[medoid][it] <= radius }.map { gs[idx[it]] }
    }

    private fun firstWindow(g: Snippet, onset: Float, frames: Int): FloatArray? {
        val r = Recording()
        val off = append(r, g, 0L)
        val start = (g.gestureStartNs + off) / 1_000_000L
        val end = (g.gestureEndNs + off) / 1_000_000L
        return Trainer.slice(r, onset, frames).firstOrNull { it.onsetMs >= start - 100 && it.onsetMs <= end }?.data
    }

    /** Appends a snippet with 1 s of still padding before and 1.5 s after. @return the time offset applied */
    private fun append(rec: Recording, s: Snippet, startNs: Long): Long {
        var t = startNs
        repeat(100) { rec.add(s.x[0], s.y[0], s.z[0], t); t += STEP_NS }
        val off = t - s.ts[0]
        for (i in 0 until s.size) rec.add(s.x[i], s.y[i], s.z[i], s.ts[i] + off)
        t = s.ts[s.size - 1] + off + STEP_NS
        repeat(150) { rec.add(s.x[s.size - 1], s.y[s.size - 1], s.z[s.size - 1], t); t += STEP_NS }
        return off
    }
}
