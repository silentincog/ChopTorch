package com.kiro.choptorch

import android.content.ClipData
import android.content.ClipboardManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kiro.choptorch.calib.CalResult
import com.kiro.choptorch.calib.CalibrationSession
import com.kiro.choptorch.calib.Calibrator
import com.kiro.choptorch.calib.Level
import com.kiro.choptorch.ml.LearnEvent
import com.kiro.choptorch.ml.ModelInfo
import com.kiro.choptorch.ml.Origin
import com.kiro.choptorch.ml.ModelStore
import com.kiro.choptorch.ml.PersonalDetector
import com.kiro.choptorch.ml.PersonalModel
import com.kiro.choptorch.ml.Strictness
import com.kiro.choptorch.ui.CalPhase
import com.kiro.choptorch.ui.CalUi
import com.kiro.choptorch.ui.Cue
import com.kiro.choptorch.ui.TestItem
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Android glue for calibration and Test & teach: reads the accelerometer at the same 100 Hz the
 * service uses, drives the prompts, and runs the analysis and training on the sensor thread.
 * The torch is paused (service suppressed) for as long as the sensor is in use here.
 */
class CalibrationController(
    private val activity: ComponentActivity,
    private val currentParams: () -> DetectorParams,
    private val hasPreset: () -> Boolean,
    /** Saves a classic setting as My preset; switches the engine to classic if asked. */
    private val onSavePreset: (DetectorParams, String, Boolean) -> Unit,
    /** Saves the model and switches the service to it. */
    private val onUseModel: (PersonalModel) -> Unit,
    /** Saves an edited model without changing which engine is used, with what was learned. */
    private val onModelChanged: (PersonalModel, LearnEvent?) -> Unit,
    private val onClose: () -> Unit,
) : CalUi, SensorEventListener {

    companion object {
        private const val REST_MS = 600L        // "Got it, rest" after each gesture
        private const val COUNT_STEP_MS = 650L  // 3, 2, 1 countdown step
        const val NORMAL_MS = 30_000L
        private const val MAX_TEST_ITEMS = 20
    }

    override val target = 8
    override val normalSeconds = (NORMAL_MS / 1000).toInt()
    override var phase by mutableStateOf(CalPhase.INTRO); private set
    override var armed by mutableStateOf(false); private set
    override var reps by mutableIntStateOf(0); private set
    override var livePeak by mutableFloatStateOf(0f); private set
    override var result by mutableStateOf<CalResult?>(null); private set
    override var previous by mutableStateOf(DetectorParams()); private set
    override var reportCopied by mutableStateOf(false); private set
    override var cue by mutableStateOf(Cue.GET_READY); private set
    override var countdown by mutableIntStateOf(3); private set
    override var level by mutableStateOf(Level.BALANCED); private set
    override var recordingGesture by mutableStateOf(false); private set
    override var normalElapsedMs by mutableStateOf(0L); private set
    override var modelInfo by mutableStateOf<ModelInfo?>(null); private set
    override var classicFalseTriggers by mutableStateOf<List<Int>?>(null); private set
    override var normalRecordedSeconds by mutableFloatStateOf(0f); private set
    override val testItems = mutableStateListOf<TestItem>()
    override var testFromResult by mutableStateOf(false); private set
    override var lastTest by mutableStateOf<TestItem?>(null); private set
    override val presetExists: Boolean get() = hasPreset()

    private val sm = activity.getSystemService(SensorManager::class.java)
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val main = Handler(Looper.getMainLooper())
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    @Volatile private var session: CalibrationSession? = null

    /** Model from this calibration run; saved only when the user picks it. */
    private var trainedModel: PersonalModel? = null
    /** Model being tried in Test & teach (a copy, swapped atomically into the detector). */
    private var testModel: PersonalModel? = null
    @Volatile private var testDetector: PersonalDetector? = null
    private val testWindows = HashMap<Int, PersonalDetector.Result>()
    private var nextTestId = 0

    /** Bumped on every start/stop so stale cue timers do nothing. */
    private var cueToken = 0

    private val ticker = object : Runnable {
        override fun run() {
            session?.let {
                livePeak = it.takePeak()
                recordingGesture = it.inGesture
                normalElapsedMs = it.normalElapsedMs
            }
            testDetector?.let { livePeak = it.takePeak() }
            main.postDelayed(this, 100)
        }
    }

    /** Called when the calibration screen opens. */
    fun open() {
        stop()
        result = null
        modelInfo = null
        trainedModel = null
        previous = currentParams()
        phase = CalPhase.INTRO
    }

    // ---------------------------------------------------------------- calibration

    override fun start() {
        val sensor = accel
        stop()
        previous = currentParams()
        reps = 0
        armed = false
        result = null
        modelInfo = null
        trainedModel = null
        classicFalseTriggers = null
        reportCopied = false
        livePeak = 0f
        normalElapsedMs = 0L
        level = Level.BALANCED
        if (sensor == null) {
            result = CalResult(null, emptyList(), 0, 0, 0, Float.NaN, Float.NaN, "No accelerometer found", emptyList(), 0f)
            phase = CalPhase.RESULT
            return
        }
        val s = CalibrationSession(target, armDelayMs = 1000L, timeoutMs = 150_000L, normalMs = NORMAL_MS)
        session = s
        listen(sensor)
        phase = CalPhase.RECORDING
        startCountdown()
    }

    /** Tells the user when to move: 3, 2, 1, then "Chop now" with a buzz. */
    private fun startCountdown() {
        val token = ++cueToken
        cue = Cue.GET_READY
        countdown = 3
        fun step(n: Int) {
            main.postDelayed({
                if (token != cueToken) return@postDelayed
                if (n > 0) {
                    countdown = n
                    step(n - 1)
                } else {
                    cue = Cue.NOW
                    buzz(CueBuzz.GO)
                }
            }, COUNT_STEP_MS)
        }
        step(2)
    }

    private fun onRepCaptured() {
        val token = ++cueToken
        cue = Cue.REST
        recordingGesture = false
        buzz(CueBuzz.TICK)
        main.postDelayed({ if (token == cueToken) startCountdown() }, REST_MS)
    }

    override fun onSensorChanged(e: SensorEvent) {
        testDetector?.let {
            it.onSample(e.values[0], e.values[1], e.values[2], e.timestamp)
            return
        }
        val s = session ?: return
        val stage = s.stage
        when (s.feed(e.values[0], e.values[1], e.values[2], e.timestamp)) {
            CalibrationSession.Signal.ARMED -> main.post { if (session === s) armed = true }
            CalibrationSession.Signal.REP -> {
                val n = s.reps
                main.post { if (session === s) { reps = n; onRepCaptured() } }
            }
            CalibrationSession.Signal.DONE -> {
                val n = s.reps
                main.post {
                    if (session !== s) return@post
                    reps = n
                    if (stage == CalibrationSession.Stage.GESTURES) goNormal() else analyse()
                }
            }
            CalibrationSession.Signal.NONE -> Unit
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** "Finish" during gestures: move on to the normal-use step. */
    override fun finish() {
        if (phase == CalPhase.RECORDING && reps >= 3) goNormal()
    }

    private fun goNormal() {
        if (phase != CalPhase.RECORDING) return
        val s = session ?: return
        cueToken++
        cue = Cue.DONE
        phase = CalPhase.NORMAL
        sensorHandler?.post { s.startNormal() }
        buzz(CueBuzz.DONE)
    }

    /** "Finish now" / "Skip" during the normal-use step. */
    override fun skipNormal() {
        if (phase == CalPhase.NORMAL) analyse()
    }

    private fun analyse() {
        val s = session ?: return
        if (phase != CalPhase.NORMAL && phase != CalPhase.RECORDING) return
        phase = CalPhase.ANALYSING
        cueToken++
        releaseSensor()
        val t = sensorThread ?: return
        val h = sensorHandler ?: return
        val maxRange = accel?.maximumRange ?: 0f
        // Runs after any sensor events still queued on this thread, so the recordings are complete.
        h.post {
            val out = s.analyzeAll(maxRange)
            main.post {
                if (session === s) {
                    result = out.cal
                    trainedModel = out.model
                    modelInfo = out.model?.let(ModelInfo::of)
                    classicFalseTriggers = out.classicFalseTriggers
                    normalRecordedSeconds = out.normalSeconds
                    phase = CalPhase.RESULT
                    buzz(CueBuzz.DONE)
                }
            }
            t.quitSafely()
        }
        sensorThread = null
        sensorHandler = null
    }

    override fun chooseLevel(l: Level) {
        level = l
    }

    /** Use the classic detector with the chosen level, saved as My preset. */
    override fun apply() {
        val r = result ?: return
        val p = r.paramsFor(level) ?: return
        onSavePreset(p, presetNote(r), true)
        close()
    }

    /** Use the personal model. The classic result is kept as My preset too, as a fallback. */
    override fun useModel() {
        val m = trainedModel ?: return
        result?.let { r -> r.paramsFor(level)?.let { onSavePreset(it, presetNote(r), false) } }
        onUseModel(m)
        close()
    }

    private fun presetNote(r: CalResult) = "Calibrated · %s · avg %.1f m/s²".format(level.label, r.averagePeak)

    override fun chooseStrictness(s: Strictness) {
        if (phase == CalPhase.TESTING) {
            val m = testModel?.copy(retrain = false) ?: return
            m.strictness = s
            m.retrain()
            swapTestModel(m)
        } else {
            val m = trainedModel ?: return
            m.strictness = s
            modelInfo = ModelInfo.of(m)
        }
    }

    // ---------------------------------------------------------------- test & teach

    /** Try the model live. From the result screen it uses the new (unsaved) model. */
    override fun openTest(fromResult: Boolean) {
        val sensor = accel ?: return
        if (fromResult) {
            val m = trainedModel?.copy() ?: return
            beginTest(sensor, m, true)
        } else {
            thread {
                val m = ModelStore.load(activity)
                main.post { if (m != null) beginTest(sensor, m, false) else close() }
            }
        }
    }

    private fun beginTest(sensor: Sensor, m: PersonalModel, fromResult: Boolean) {
        stop()
        testFromResult = fromResult
        testItems.clear()
        testWindows.clear()
        lastTest = null
        testModel = m
        modelInfo = ModelInfo.of(m)
        testDetector = PersonalDetector(m, onTrigger = {}, reportGentle = true) { r -> main.post { addTestItem(r) } }
            .also { it.cooldownMs = Prefs.cooldownMs(activity) }
        listen(sensor)
        phase = CalPhase.TESTING
    }

    private fun addTestItem(r: PersonalDetector.Result) {
        if (phase != CalPhase.TESTING) return
        val id = nextTestId++
        testWindows[id] = r
        val item = TestItem(id, r.percent, r.matched, r.peak, r.gentle)
        testItems.add(0, item)
        while (testItems.size > MAX_TEST_ITEMS) testWindows.remove(testItems.removeAt(testItems.lastIndex).id)
        lastTest = item
        if (r.matched) buzz(CueBuzz.TICK)
    }

    override fun teach(id: Int, wasGesture: Boolean) {
        val r = testWindows[id] ?: return
        val m = testModel?.copy(retrain = false) ?: return
        val sid = if (wasGesture) m.addPositive(r.window, r.peak, Origin.TAUGHT) else m.addNegative(r.window, Origin.TAUGHT)
        swapTestModel(m, LearnEvent(sid, System.currentTimeMillis(), wasGesture, Origin.TAUGHT, r.percent))
        val i = testItems.indexOfFirst { it.id == id }
        if (i >= 0) testItems[i] = testItems[i].copy(taught = wasGesture)
        if (lastTest?.id == id) lastTest = testItems.getOrNull(i)
    }

    private fun swapTestModel(m: PersonalModel, event: LearnEvent? = null) {
        testModel = m
        testDetector?.model = m
        modelInfo = ModelInfo.of(m)
        if (testFromResult) {
            trainedModel = m.copy()
        } else {
            val snapshot = m.copy(retrain = false)
            thread { onModelChanged(snapshot, event) }
        }
    }

    override fun closeTest() {
        val fromResult = testFromResult
        stop()
        if (fromResult) {
            modelInfo = trainedModel?.let(ModelInfo::of)
            phase = CalPhase.RESULT
        } else {
            close()
        }
    }

    // ---------------------------------------------------------------- common

    override fun close() {
        stop()
        phase = CalPhase.INTRO
        onClose()
    }

    override fun copyReport() {
        val r = result ?: return
        val device = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE}, ${Build.DISPLAY}"
        val sensor = accel?.let {
            String.format(
                Locale.US, "%s by %s, range %.1f m/s², resolution %.4f, min delay %d µs",
                it.name, it.vendor, it.maximumRange, it.resolution, it.minDelay,
            )
        } ?: "none"
        val text = buildString {
            append(Calibrator.report(r, device, sensor, previous, level))
            append(String.format(Locale.US, "Normal use recorded: %.0f s\n", normalRecordedSeconds))
            classicFalseTriggers?.let { f ->
                appendLine(Level.entries.joinToString(", ", "Classic triggers in normal use: ") { "${it.label} ${f[it.ordinal]}" })
            }
            trainedModel?.let { m ->
                appendLine(m.summary())
                val st = m.stats
                appendLine(
                    Strictness.entries.joinToString(", ", "Model (leave-one-out) recognised / normal-use triggers: ") {
                        "${it.label} ${st.recognised[it.ordinal]}/${st.positives} / ${st.falseTriggers[it.ordinal]}"
                    },
                )
            } ?: appendLine("Model: not trained (needs at least 3 gestures)")
        }
        val cm = activity.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Chop Torch calibration report", text))
        reportCopied = true
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(activity, "Report copied", Toast.LENGTH_SHORT).show()
    }

    /** The activity went to the background: abandon recording or testing in progress. */
    fun onHostStopped() {
        when (phase) {
            CalPhase.RECORDING, CalPhase.NORMAL -> { stop(); phase = CalPhase.INTRO }
            CalPhase.TESTING -> closeTest()
            else -> Unit
        }
    }

    private fun listen(sensor: Sensor) {
        val t = HandlerThread("chop-calibration").also { it.start() }
        sensorThread = t
        sensorHandler = Handler(t.looper)
        ChopService.suppressed = true
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sm.registerListener(this, sensor, 10_000, sensorHandler)
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    private fun releaseSensor() {
        sm.unregisterListener(this)
        main.removeCallbacks(ticker)
        ChopService.suppressed = false
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun stop() {
        cueToken++
        releaseSensor()
        session = null
        testDetector = null
        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null
    }

    private enum class CueBuzz { GO, TICK, DONE }

    /** Calibration cues use the same strength as the torch buzz: GO = one buzz, TICK = short, DONE = two. */
    private fun buzz(kind: CueBuzz) = Haptics.play(
        activity,
        when (kind) {
            CueBuzz.GO -> com.kiro.choptorch.Buzz.ON
            CueBuzz.TICK -> com.kiro.choptorch.Buzz.TICK
            CueBuzz.DONE -> com.kiro.choptorch.Buzz.OFF
        },
    )
}
