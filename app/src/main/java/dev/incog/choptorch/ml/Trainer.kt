package dev.incog.choptorch.ml

import dev.incog.choptorch.calib.BurstSegmenter
import dev.incog.choptorch.calib.Recording
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Builds a [PersonalModel] from the calibration recordings. */
object Trainer {
    const val MIN_POSITIVES = 3

    /**
     * @param gestures the recording made during the "Chop now" prompts
     * @param bursts the gestures found in it
     * @param ignored per burst: true if calibration ignored it as much weaker than the others
     * @param peaks per burst: peak force (m/s²)
     * @param normal optional recording of everyday handling (no gestures)
     */
    fun train(
        gestures: Recording,
        bursts: List<BurstSegmenter.Burst>,
        ignored: List<Boolean>,
        peaks: List<Float>,
        normal: Recording?,
    ): PersonalModel? {
        val keep = bursts.indices.filter { !ignored[it] }
        if (keep.size < MIN_POSITIVES) return null

        val durations = keep.map { (bursts[it].endMs - bursts[it].startMs).toFloat() }.sorted()
        val p90 = durations[((durations.size - 1) * 0.9f).roundToInt()]
        val frames = ((p90 + 160f) / Frontend.FRAME_MS).roundToInt().coerceIn(15, 80)
        val median = keep.map { peaks[it] }.sorted()[keep.size / 2]
        val onset = (median * 0.35f).coerceIn(4f, 12f)

        // Slice the gesture recording the same way live detection will.
        val windows = slice(gestures, onset, frames)
        val positives = ArrayList<FloatArray>()
        val posPeaks = ArrayList<Float>()
        val hardNegatives = ArrayList<FloatArray>()
        for (w in windows) {
            val bi = bursts.indexOfFirst { w.onsetMs >= it.startMs - 100 && w.onsetMs <= it.endMs }
            when {
                bi < 0 -> if (w.peak >= onset) hardNegatives += w.data          // handling between gestures
                ignored[bi] -> Unit
                positives.size < keep.size && isFirstIn(bi, w, windows, bursts) -> {
                    positives += w.data; posPeaks += w.peak
                }
                else -> hardNegatives += w.data                                   // part of a gesture only
            }
        }
        if (positives.size < MIN_POSITIVES) return null
        val minPeak = posPeaks.min() * 0.5f

        val normalWindows = normal?.let { rec -> slice(rec, onset, frames).filter { it.peak >= minPeak * 0.5f }.map { it.data } }
            ?: emptyList()

        var id = 1L
        val now = System.currentTimeMillis()
        val pos = positives.takeLast(PersonalModel.MAX_POSITIVES).map { Sample(id++, it, Origin.CALIBRATION, now) }
        val neg = (normalWindows.map { it to Origin.NORMAL_USE } + hardNegatives.map { it to Origin.CALIBRATION })
            .take(PersonalModel.MAX_NEGATIVES)
            .map { (w, o) -> Sample(id++, w, o, now) }
        val model = PersonalModel(
            windowFrames = frames,
            onsetLevel = onset,
            minPeak = minPeak,
            positives = pos.toMutableList(),
            negatives = neg.toMutableList(),
            synthetic = synthetic(onset, frames).toMutableList(),
            normalSeconds = (normal?.durationMs ?: 0L) / 1000f,
            nextId = id,
        )
        model.retrain()
        return model
    }

    private fun isFirstIn(bi: Int, w: Window, all: List<Window>, bursts: List<BurstSegmenter.Burst>): Boolean {
        val b = bursts[bi]
        return all.first { it.onsetMs >= b.startMs - 100 && it.onsetMs <= b.endMs } === w
    }

    fun slice(rec: Recording, onset: Float, frames: Int): List<Window> {
        val out = ArrayList<Window>()
        val fe = Frontend(onset, frames) { out += it }
        for (i in 0 until rec.size) fe.onSample(rec.x[i], rec.y[i], rec.z[i], rec.ts[i])
        return out
    }

    /** Walking and running in each axis, so the model learns those are not the gesture. */
    private fun synthetic(onset: Float, frames: Int): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        val kinds = listOf(Triple(1.8, 5.0, 3.0), Triple(2.8, 11.0, 5.0))
        for ((hz, vert, side) in kinds) {
            for (axis in 0..2) {
                val rec = Recording()
                for (i in 0 until 1000) {
                    val t = i / 100.0
                    val v = FloatArray(3)
                    v[axis] = (9.81 + vert * sin(2 * PI * hz * t)).toFloat()
                    v[(axis + 1) % 3] = (side * sin(PI * hz * t)).toFloat()
                    rec.add(v[0], v[1], v[2], i * 10_000_000L)
                }
                out += slice(rec, onset, frames).map { it.data }.take(8)
            }
        }
        return out
    }
}
