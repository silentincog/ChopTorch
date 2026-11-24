package dev.incog.choptorch.ml

import dev.incog.choptorch.ChopDetector
import dev.incog.choptorch.DetectorParams
import dev.incog.choptorch.GestureEngine
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Auto mode: the classic double-chop detector, tuning its own trigger force to the force you
 * actually use, and collecting your confirmed gestures so a personal model can be trained later.
 *
 * Two detectors run on the same samples:
 * - primary, at the current trigger force: it switches the torch;
 * - shadow, at 60 % of it: it never switches the torch; it only notices softer double chops.
 *
 * A gesture's force counts as "yours" when you confirm it by what you do next:
 * - primary switched the torch and you kept it for [CONFIRM_MS];
 * - shadow noticed a soft one, then you repeated it and primary switched the torch within [RETRY_MS];
 * - shadow noticed a soft one, nothing switched, and you used the torch by hand within [MISS_MS].
 * A switch you undo by hand within [UNDO_MS], or with "Wasn't me", counts as a false trigger.
 *
 * Trigger force = [RATIO] x the median of your last [KEEP] confirmed forces, raised if needed to
 * stay above soft false triggers, kept in [MIN_T]..[MAX_T]. It starts adapting after [START_AFTER].
 * Clear confirmations (repeating it, or the torch tile) count double, because "kept the torch on"
 * also happens after a false trigger nobody noticed.
 *
 * Single-threaded: call everything on the sensor thread. Time is the sensor timestamp.
 */
class AutoPilot(
    private val timing: DetectorParams,
    initialThreshold: Float,
    initialForces: List<Float>,
    initialFalse: List<Float>,
    private val cb: Callbacks,
) : GestureEngine {

    interface Callbacks {
        /** The primary detector matched: switch the torch if allowed, then call [onToggled]. */
        fun trigger()
        fun thresholdChanged(threshold: Float, forces: List<Float>, falseForces: List<Float>)
        fun gestureCollected(s: Snippet)
        fun everydayCollected(s: Snippet)
        /** A trigger you switched back or marked "Wasn't me": an example of what to ignore. */
        fun falseCollected(s: Snippet) {}
        fun log(msg: String) {}
    }

    companion object {
        const val SHADOW_RATIO = 0.6f
        const val SHADOW_MIN = 7f
        const val MIN_T = 8f
        const val MAX_T = 30f
        const val RATIO = 0.7f
        const val START_AFTER = 3
        const val KEEP = 20
        const val SAME_MS = 600L
        const val RETRY_MS = 3_000L
        const val MISS_MS = 6_000L
        const val UNDO_MS = 3_000L
        const val CONFIRM_MS = 8_000L
        const val WRONG_MS = 10 * 60_000L
        private const val SNIP_BEFORE_MS = 700L
        private const val SNIP_AFTER_MS = 900L
        private const val EVERYDAY_EVERY_MS = 90_000L
        private const val EVERYDAY_CLEAR_MS = 10_000L
        private const val ACTIVE = 6f
        private const val MS = 1_000_000L

        /** The trigger force for these confirmed forces (null until there are enough). */
        fun target(forces: List<Float>, falseForces: List<Float>, strong: List<Boolean> = emptyList()): Float? {
            if (forces.size < START_AFTER) return null
            val last = forces.takeLast(KEEP)
            val st = strong.takeLast(KEEP)
            val recent = last.flatMapIndexed { i, f -> if (st.getOrElse(i) { false }) listOf(f, f) else listOf(f) }.sorted()
            val med = recent[recent.size / 2]
            var t = RATIO * med
            for (f in falseForces.takeLast(10)) {
                if (f < med * 0.9f && f * 1.05f > t) t = minOf(f * 1.05f, med * 0.9f)
            }
            return t.coerceIn(MIN_T, MAX_T)
        }
    }

    private class Cand(val force: Float, val startNs: Long, val fireNs: Long) {
        var snippet: Snippet? = null
        var used = false
        var confirmed = false
        var strong = false
        /** What to do with the snippet once it is captured: 1 = gesture, 2 = false trigger. */
        var deliver = 0
        var toggledNs = -1L
    }

    private class Entry(val cand: Cand?, val force: Float, val strong: Boolean)

    var threshold = initialThreshold.coerceIn(MIN_T, MAX_T)
        private set
    /** Confirmed forces, oldest first, with the candidate they came from (null if restored). */
    private val forces = ArrayDeque<Entry>().apply { initialForces.takeLast(KEEP).forEach { addLast(Entry(null, it, false)) } }
    private val falseForces = ArrayDeque<Float>().apply { initialFalse.takeLast(10).forEach { addLast(it) } }

    private val primary = ChopDetector(timing.copy(threshold = threshold), onDoubleChop = {}, onEvent = { onEvent(it, true) })
    private val shadow = ChopDetector(timing.copy(threshold = shadowThreshold()), onDoubleChop = {}, onEvent = { onEvent(it, false) })
    private val ring = RawRing(1000)
    private var nowNs = 0L

    private var pChop1 = 0f; private var pChop1Ns = 0L
    private var sChop1 = 0f; private var sChop1Ns = 0L
    private var lastPrimary: Cand? = null
    private var lastToggled: Cand? = null
    private var pendingConfirm: Cand? = null
    private val shadows = ArrayDeque<Cand>()
    private val waitingSnippet = ArrayList<Cand>()

    private var lastActiveNs = Long.MIN_VALUE / 2
    private var lastFireNs = Long.MIN_VALUE / 2
    private var lastEverydayNs = Long.MIN_VALUE / 2
    private var everydayFrom = -1L
    private var everydayTo = -1L
    private var everydaySnippet: Snippet? = null
    private var fireAtEverydayStart = 0L

    val confirmedCount: Int get() = forces.size

    private fun shadowThreshold() = maxOf(SHADOW_MIN, threshold * SHADOW_RATIO)

    override fun takePeak(): Float = primary.takePeak()

    override var cooldownMs: Long
        get() = primary.cooldownMs
        set(v) {
            primary.cooldownMs = v
            shadow.cooldownMs = v
        }

    override fun restart() {
        primary.restart()
        shadow.restart()
    }

    override fun onSample(ax: Float, ay: Float, az: Float, tsNs: Long) {
        nowNs = tsNs
        ring.add(ax, ay, az, tsNs)
        primary.onSample(ax, ay, az, tsNs)
        shadow.onSample(ax, ay, az, tsNs)
        if (primary.lastMag > ACTIVE) lastActiveNs = tsNs

        // Keep a gesture's raw data once enough time after it has passed.
        if (waitingSnippet.isNotEmpty()) {
            val it = waitingSnippet.iterator()
            while (it.hasNext()) {
                val c = it.next()
                if (tsNs >= c.fireNs + SNIP_AFTER_MS * MS) {
                    c.snippet = ring.slice(c.startNs - SNIP_BEFORE_MS * MS, c.fireNs + SNIP_AFTER_MS * MS, c.startNs, c.fireNs + 150 * MS, c.force)
                    it.remove()
                    deliver(c)
                }
            }
        }

        pendingConfirm?.let { p ->
            if (tsNs - p.toggledNs >= CONFIRM_MS * MS) {
                pendingConfirm = null
                if (!p.used) { p.used = true; confirm(p, "kept the torch", strong = false) }
            }
        }
        while (shadows.isNotEmpty() && tsNs - shadows.first().fireNs > MISS_MS * MS) shadows.removeFirst()
        everyday(tsNs)
    }

    /** Collects a few seconds of everyday movement now and then, well away from any gesture. */
    private fun everyday(t: Long) {
        if (everydayFrom < 0) {
            if (t - lastEverydayNs > EVERYDAY_EVERY_MS * MS && t - lastActiveNs < 300 * MS && t - lastFireNs > EVERYDAY_CLEAR_MS * MS) {
                everydayFrom = t - 1000 * MS
                everydayTo = t + 2000 * MS
                fireAtEverydayStart = lastFireNs
            }
            return
        }
        // Any double chop (even a soft one) near it means it may not be "everyday": drop it.
        if (lastFireNs != fireAtEverydayStart) { everydayFrom = -1L; everydaySnippet = null; return }
        if (everydaySnippet == null && t >= everydayTo) everydaySnippet = ring.slice(everydayFrom, everydayTo)
        if (everydaySnippet != null && t >= everydayTo + EVERYDAY_CLEAR_MS * MS) {
            cb.everydayCollected(everydaySnippet!!)
            lastEverydayNs = t
            everydayFrom = -1L
            everydaySnippet = null
        }
    }

    private fun onEvent(e: ChopDetector.Event, isPrimary: Boolean) {
        when (e.kind) {
            ChopDetector.Kind.CHOP1 -> if (isPrimary) { pChop1 = e.peak; pChop1Ns = nowNs } else { sChop1 = e.peak; sChop1Ns = nowNs }
            ChopDetector.Kind.FIRE -> {
                lastFireNs = nowNs
                if (isPrimary) {
                    val c = Cand(maxOf(pChop1, e.peak), pChop1Ns - 250 * MS, nowNs)
                    shadows.removeAll { abs(it.fireNs - nowNs) <= SAME_MS * MS }
                    lastPrimary = c
                    waitingSnippet += c
                    cb.log("auto: double chop, force %.1f, trigger %.1f".format(c.force, threshold))
                    cb.trigger()
                } else {
                    val p = lastPrimary
                    if (p != null && abs(p.fireNs - nowNs) <= SAME_MS * MS) return
                    val c = Cand(maxOf(sChop1, e.peak), sChop1Ns - 250 * MS, nowNs)
                    shadows.addLast(c)
                    waitingSnippet += c
                    cb.log("auto: soft double chop noticed, force %.1f (trigger %.1f)".format(c.force, threshold))
                }
            }
            else -> Unit
        }
    }

    /** The last trigger really switched the torch. [now] is in sensor time. */
    fun onToggled(now: Long = nowNs) {
        val c = lastPrimary ?: return
        c.toggledNs = now
        val soft = shadows.filter { !it.used && c.fireNs - it.fireNs in (SAME_MS * MS)..(RETRY_MS * MS) }.maxByOrNull { it.force }
        if (soft != null) { soft.used = true; confirm(soft, "you repeated it harder", strong = true) }
        lastToggled = c
        pendingConfirm = c
    }

    /**
     * The torch changed without the gesture. [now] is in sensor time; pass the real current time,
     * because the sensor is paused while the phone is unlocked and [nowNs] is then out of date.
     */
    fun onManualTorchChange(now: Long = nowNs) {
        val t = lastToggled
        if (t != null && !t.used && now - t.toggledNs <= UNDO_MS * MS) {
            t.used = true
            if (pendingConfirm === t) pendingConfirm = null
            falseTrigger(t, "switched back right away")
            return
        }
        if (t == null || now - t.toggledNs > MISS_MS * MS) {
            val soft = shadows.filter { !it.used && now - it.fireNs <= MISS_MS * MS }.maxByOrNull { it.force }
            if (soft != null) { soft.used = true; confirm(soft, "you used the torch by hand", strong = true) }
        }
    }

    /** "Wasn't me" from the notification. [now] is in sensor time. */
    fun onWrong(now: Long = nowNs) {
        val t = lastToggled ?: return
        if (now - t.toggledNs > WRONG_MS * MS) return
        if (pendingConfirm === t) pendingConfirm = null
        if (t.confirmed) forces.removeAll { it.cand === t }
        t.used = true
        falseTrigger(t, "you said it wasn't you")
    }

    private fun confirm(c: Cand, why: String, strong: Boolean) {
        c.confirmed = true
        c.strong = strong
        forces.addLast(Entry(c, c.force, strong))
        while (forces.size > KEEP) forces.removeFirst()
        cb.log("auto: learned your force %.1f (%s)".format(c.force, why))
        c.deliver = 1
        deliver(c)
        update()
    }

    private fun falseTrigger(c: Cand, why: String) {
        falseForces.addLast(c.force)
        while (falseForces.size > 10) falseForces.removeFirst()
        cb.log("auto: false trigger at force %.1f (%s)".format(c.force, why))
        c.deliver = 2
        deliver(c)
        update()
    }

    private fun deliver(c: Cand) {
        val s = c.snippet ?: return
        when (c.deliver) {
            1 -> cb.gestureCollected(
                Snippet(s.ts, s.x, s.y, s.z, s.gestureStartNs, s.gestureEndNs, s.force, strong = c.strong),
            )
            2 -> cb.falseCollected(s)
        }
        c.deliver = 0
    }

    private fun update() {
        val f = forces.map { it.force }
        val ff = falseForces.toList()
        val t = target(f, ff, forces.map { it.strong })
        if (t != null) {
            // Jump straight there at first, then move halfway each time so one odd gesture can't swing it.
            val next = if (f.size < 5) t else threshold + (t - threshold) * 0.5f
            threshold = ((next * 2f).roundToInt() / 2f).coerceIn(MIN_T, MAX_T)
            primary.params = timing.copy(threshold = threshold)
            shadow.params = timing.copy(threshold = shadowThreshold())
        }
        cb.thresholdChanged(threshold, f, ff)
    }
}
