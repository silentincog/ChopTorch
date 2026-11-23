package com.kiro.choptorch.ml

import android.content.Context
import com.kiro.choptorch.Prefs
import java.io.File

/**
 * Where the personal model and its learning history live: device-protected storage, so they work
 * before first unlock. The app's screens and the background service run in one process; every
 * read-modify-write goes through [lock] so learning in the service and edits on screen never
 * overwrite each other.
 */
object ModelStore {
    private val lock = Any()

    fun file(ctx: Context): File = File(dir(ctx), "personal_model.bin")
    fun dir(ctx: Context): File = ctx.applicationContext.createDeviceProtectedStorageContext().filesDir

    fun load(ctx: Context): PersonalModel? = synchronized(lock) {
        file(ctx).takeIf { it.exists() }?.let(PersonalModel::load)
    }

    /** Replaces the model (new calibration or Test & teach edit). */
    fun save(ctx: Context, model: PersonalModel, event: LearnEvent? = null, clearLog: Boolean = false) {
        synchronized(lock) {
            if (clearLog) LearningLog.clear(dir(ctx))
            event?.let { LearningLog.add(dir(ctx), it) }
            model.save(file(ctx))
        }
        bumpRev(ctx)
    }

    /**
     * Loads, changes and saves the model atomically.
     * [block] may add to the learning history through the [LogWriter] it receives.
     * @return what [block] returned, or null if there is no model
     */
    fun <T> update(ctx: Context, block: (PersonalModel, LogWriter) -> T): T? {
        val out = synchronized(lock) {
            val m = file(ctx).takeIf { it.exists() }?.let(PersonalModel::load) ?: return null
            val writer = LogWriter(dir(ctx))
            val r = block(m, writer)
            m.save(file(ctx))
            r
        }
        bumpRev(ctx)
        return out
    }

    fun history(ctx: Context): List<LearnEvent> = synchronized(lock) { LearningLog.read(dir(ctx)) }

    private fun bumpRev(ctx: Context) {
        val p = Prefs.get(ctx)
        p.edit().putInt(Prefs.MODEL_REV, p.getInt(Prefs.MODEL_REV, 0) + 1).apply()
    }

    class LogWriter internal constructor(private val dir: File) {
        fun add(e: LearnEvent) = LearningLog.add(dir, e)
        fun markUndone(sampleId: Long) = LearningLog.markUndone(dir, sampleId)
        fun clear() = LearningLog.clear(dir)
    }
}

/** One thing the model learned. */
data class LearnEvent(
    val sampleId: Long,
    val timeMs: Long,
    val positive: Boolean,
    val origin: Origin,
    val percent: Int,
    val undone: Boolean = false,
)

/** Last [MAX] learning events as tab-separated lines, newest last. */
internal object LearningLog {
    private const val MAX = 60
    private fun file(dir: File) = File(dir, "learning_log.tsv")

    fun read(dir: File): List<LearnEvent> {
        val f = file(dir)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            val p = line.split('\t')
            runCatching {
                LearnEvent(p[0].toLong(), p[1].toLong(), p[2] == "1", Origin.valueOf(p[3]), p[4].toInt(), p[5] == "1")
            }.getOrNull()
        }.reversed()
    }

    fun add(dir: File, e: LearnEvent) = write(dir, read(dir).reversed() + e)

    fun markUndone(dir: File, sampleId: Long) =
        write(dir, read(dir).reversed().map { if (it.sampleId == sampleId) it.copy(undone = true) else it })

    fun clear(dir: File) {
        file(dir).delete()
    }

    private fun write(dir: File, events: List<LearnEvent>) {
        val lines = events.takeLast(MAX).joinToString("\n") {
            listOf(it.sampleId, it.timeMs, if (it.positive) 1 else 0, it.origin.name, it.percent, if (it.undone) 1 else 0)
                .joinToString("\t")
        }
        val tmp = File(dir, "learning_log.tsv.tmp")
        tmp.writeText(lines)
        if (!tmp.renameTo(file(dir))) {
            file(dir).delete()
            tmp.renameTo(file(dir))
        }
    }
}

/** What the UI shows about a model. Immutable snapshot, safe to pass between threads. */
data class ModelInfo(
    val strictness: Strictness,
    val stats: ModelStats,
    val positives: Int,
    val negatives: Int,
    val learnedPositives: Int = 0,
    val learnedNegatives: Int = 0,
) {
    companion object {
        fun of(m: PersonalModel) =
            ModelInfo(m.strictness, m.stats, m.positives.size, m.negatives.size, m.learnedPositives, m.learnedNegatives)
    }

    val recognised: Int get() = stats.recognised[strictness.ordinal]
    val falseTriggers: Int get() = stats.falseTriggers[strictness.ordinal]
}
