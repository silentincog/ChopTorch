package dev.incog.choptorch.ml

/**
 * Learns from everyday use, on the phone, by turning what you do next into labels.
 *
 * Signals (all optional, all undoable):
 * - Near miss, then you repeat the gesture within [RETRY_MS] and it works: the near miss was your gesture.
 * - Near miss, nothing triggered, and you switch the torch by hand within [MISS_MS]: same.
 * - The gesture switched the torch and you switch it back by hand within [UNDO_MS]: false trigger.
 * - Two similar, firm movements that both missed, then the torch switched by hand within [MISS_MS]:
 *   you tried your gesture twice, so both are your gesture. This is how it catches up when your
 *   gesture changes a lot at once. The two must be closer to each other than [REPEAT_FACTOR] x the
 *   Relaxed cutoff, closer to each other than to anything known not to be the gesture, and have
 *   the same burst structure as your gesture; there must be only 2-3 firm movements in that time
 *   (walking makes many).
 * - "Wasn't me" in the notification while the torch is on: false trigger.
 * - A borderline match ([CONFIRM_MIN_PCT]..[CONFIRM_MAX_PCT] %) that you keep for [CONFIRM_MS]:
 *   that variant of your gesture is confirmed.
 *
 * Only movements that were already close to your gesture (at least [NEAR_MISS_PCT] %) can become
 * new gesture samples, so picking the phone up and then using the Quick Settings torch tile does
 * not teach it that picking up is the gesture. At most [maxPerDay] lessons are learned per day,
 * and at most [maxConfirmPerDay] of them may be confirmations, so routine use can't crowd out
 * corrections.
 *
 * Not thread-safe: call everything from one thread (the service's main thread).
 */
class OnlineLearner(
    private val learn: (Lesson) -> Unit,
    /** The model currently in use, for the repeated-attempt check. */
    private val judge: () -> Judge? = { null },
    private val maxPerDay: Int = 40,
    private val maxConfirmPerDay: Int = 12,
    private val log: (String) -> Unit = {},
    /** You used the torch by hand right after firm movements it didn't recognise or learn. */
    private val onStruggle: () -> Unit = {},
) {
    class Lesson(
        val positive: Boolean,
        val window: FloatArray,
        val peak: Float,
        val origin: Origin,
        val percent: Int,
        /** Same key as an earlier AUTO_CONFIRMED lesson that this one contradicts. */
        val key: Long,
    )

    companion object {
        const val NEAR_MISS_PCT = 35
        const val CONFIRM_MIN_PCT = 50
        const val CONFIRM_MAX_PCT = 75
        const val RETRY_MS = 3_000L
        const val MISS_MS = 6_000L
        const val UNDO_MS = 3_000L
        const val CONFIRM_MS = 8_000L
        const val WRONG_MS = 10 * 60_000L
        const val REPEAT_FACTOR = 1.5f
        const val REPEAT_MIN_APART_MS = 600L
        private const val DAY_MS = 24 * 60 * 60_000L
    }

    private class Seen(val r: PersonalDetector.Result, val at: Long, var used: Boolean = false)

    @Volatile var enabled = true
    private val nearMisses = ArrayDeque<Seen>()
    /** Every firm movement that didn't match, for the repeated-attempt rule. */
    private val firmMisses = ArrayDeque<Seen>()
    private var lastTrigger: Seen? = null
    private var pendingConfirm: Seen? = null
    private val learnedAt = ArrayDeque<Long>()
    private val confirmedAt = ArrayDeque<Long>()

    /** A movement was compared and did not match. */
    fun onNoMatch(r: PersonalDetector.Result, now: Long) {
        prune(now)
        if (r.gentle || r.matched) return
        val seen = Seen(r, now)
        firmMisses.addLast(seen)
        if (r.percent >= NEAR_MISS_PCT) nearMisses.addLast(seen)
    }

    /** A match actually switched the torch. */
    fun onTriggered(r: PersonalDetector.Result, now: Long) {
        prune(now)
        val prevAt = lastTrigger?.at ?: Long.MIN_VALUE
        val miss = nearMisses.filter { !it.used && now - it.at <= RETRY_MS && it.at > prevAt }.maxByOrNull { it.r.percent }
        if (miss != null) {
            miss.used = true
            teach(true, miss, Origin.AUTO_RETRY)
        }
        val seen = Seen(r, now)
        lastTrigger = seen
        pendingConfirm = if (r.percent in CONFIRM_MIN_PCT until CONFIRM_MAX_PCT) seen else null
    }

    /** The torch changed without the gesture (Quick Settings tile, power button, another app). */
    fun onManualTorchChange(now: Long) {
        prune(now)
        val t = lastTrigger
        if (t != null && !t.used && now - t.at <= UNDO_MS) {
            t.used = true
            if (pendingConfirm === t) pendingConfirm = null
            teach(false, t, Origin.AUTO_UNDO)
            return
        }
        if (t == null || now - t.at > MISS_MS) {
            val j = judge()
            val miss = nearMisses.filter { !it.used && now - it.at <= MISS_MS && (j == null || j.plausible(it.r.window)) }
                .maxByOrNull { it.r.percent }
            if (miss != null) {
                miss.used = true
                teach(true, miss, Origin.AUTO_MISS)
                return
            }
            if (!repeatedAttempt(now) && firmMisses.any { !it.used && now - it.at <= MISS_MS }) onStruggle()
        }
    }

    /** @return true if it learned something */
    private fun repeatedAttempt(now: Long): Boolean {
        val j = judge() ?: return false
        val firm = firmMisses.filter { !it.used && now - it.at <= MISS_MS && it.r.peak >= j.minPeak }
        if (firm.size !in 2..3) return false
        if (firm.any { !j.plausible(it.r.window) }) return false
        var best: Pair<Seen, Seen>? = null
        var bestD = Float.MAX_VALUE
        for (i in firm.indices) for (k in i + 1 until firm.size) {
            if (firm[k].at - firm[i].at < REPEAT_MIN_APART_MS) continue
            val d = j.pairDistance(firm[i].r.window, firm[k].r.window)
            if (d < bestD) { bestD = d; best = firm[i] to firm[k] }
        }
        val (a, b) = best ?: return false
        if (bestD > j.looseCutoff * REPEAT_FACTOR) return false
        if (bestD >= j.nearestNegative(a.r.window) || bestD >= j.nearestNegative(b.r.window)) return false
        a.used = true; b.used = true
        teach(true, a, Origin.AUTO_REPEAT)
        teach(true, b, Origin.AUTO_REPEAT)
        return true
    }

    /** "Wasn't me" from the notification. @return true if there was a trigger to learn from */
    fun onWrong(now: Long): Boolean {
        val t = lastTrigger ?: return false
        if (t.used && t.r.percent !in CONFIRM_MIN_PCT until CONFIRM_MAX_PCT) return false
        if (now - t.at > WRONG_MS) return false
        if (pendingConfirm === t) pendingConfirm = null
        t.used = true
        teach(false, t, Origin.NOTIFICATION, force = true)
        return true
    }

    /** Call regularly (about once a second). */
    fun tick(now: Long) {
        val p = pendingConfirm ?: return
        if (now - p.at >= CONFIRM_MS) {
            pendingConfirm = null
            teach(true, p, Origin.AUTO_CONFIRMED)
        }
    }

    private fun teach(positive: Boolean, s: Seen, origin: Origin, force: Boolean = false) {
        if (!enabled) return
        val now = s.at
        while (learnedAt.isNotEmpty() && now - learnedAt.first() > DAY_MS) learnedAt.removeFirst()
        while (confirmedAt.isNotEmpty() && now - confirmedAt.first() > DAY_MS) confirmedAt.removeFirst()
        if (origin == Origin.AUTO_CONFIRMED && confirmedAt.size >= maxConfirmPerDay) return
        if (!force && learnedAt.size >= maxPerDay) {
            log("learning limit for today reached, skipped one lesson")
            return
        }
        learnedAt.addLast(now)
        if (origin == Origin.AUTO_CONFIRMED) confirmedAt.addLast(now)
        learn(Lesson(positive, s.r.window, s.r.peak, origin, s.r.percent, s.r.onsetMs))
    }

    private fun prune(now: Long) {
        while (nearMisses.isNotEmpty() && now - nearMisses.first().at > MISS_MS) nearMisses.removeFirst()
        while (firmMisses.isNotEmpty() && now - firmMisses.first().at > MISS_MS) firmMisses.removeFirst()
    }
}
