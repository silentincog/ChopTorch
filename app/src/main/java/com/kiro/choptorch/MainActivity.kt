package com.kiro.choptorch

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kiro.choptorch.ml.LearnEvent
import com.kiro.choptorch.ml.ModelInfo
import com.kiro.choptorch.ml.ModelStore
import com.kiro.choptorch.ml.PersonalModel
import com.kiro.choptorch.ml.Strictness
import com.kiro.choptorch.ui.AutoInfo
import com.kiro.choptorch.ui.CalibrationScreen
import com.kiro.choptorch.ui.ChopScreen
import com.kiro.choptorch.ui.ChopTheme
import com.kiro.choptorch.ui.ChopUi
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class MainActivity : ComponentActivity(), ChopUi {

    private val prefs by lazy { Prefs.get(this) }
    private lateinit var torch: Torch
    private lateinit var calibration: CalibrationController
    private var showCalibration by mutableStateOf(false)

    override var enabled by mutableStateOf(false); private set
    override var running by mutableStateOf(false); private set
    override var listening by mutableStateOf(false); private set
    override var onlyLocked by mutableStateOf(false); private set
    override var cooldownSec by mutableFloatStateOf(ChopDetector.COOLDOWN_MS / 1000f); private set
    override var torchOn by mutableStateOf(false); private set
    override var params by mutableStateOf(DetectorParams()); private set
    override val threshold: Float get() = params.threshold
    override var myPreset by mutableStateOf<DetectorParams?>(null); private set
    override var myPresetNote by mutableStateOf(""); private set
    override var engine by mutableStateOf(Engine.AUTO); private set
    override var auto by mutableStateOf(AutoInfo()); private set
    override var model by mutableStateOf<ModelInfo?>(null); private set
    override var learnEnabled by mutableStateOf(true); private set
    override var learnEvents by mutableStateOf<List<LearnEvent>>(emptyList()); private set
    override var strugglesToday by mutableStateOf(0); private set

    /** The service learns in the background; refresh what we show whenever the model file changes. */
    private val modelRevListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.MODEL_REV) reloadModelInfo()
        if (key in Prefs.AUTO_KEYS) reloadAuto()
        if (key == Prefs.ENGINE) engine = Prefs.engine(this)
        if (key == Prefs.STRUGGLES) strugglesToday = Prefs.struggles(this)
    }
    override var screenOff by mutableStateOf(true); private set
    override var pocketGuard by mutableStateOf(true); private set
    override var vibrate by mutableStateOf(true); private set
    override var batteryExempt by mutableStateOf(false); private set
    override var rootBusy by mutableStateOf(false); private set
    override var peak by mutableFloatStateOf(0f); private set
    override val log = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        enabled = Prefs.enabled(this)
        params = Prefs.params(this)
        myPreset = Prefs.myPreset(this)
        myPresetNote = Prefs.myPresetNote(this)
        screenOff = Prefs.screenOff(this)
        onlyLocked = Prefs.onlyLocked(this)
        cooldownSec = Prefs.cooldownMs(this) / 1000f
        pocketGuard = Prefs.pocketGuard(this)
        vibrate = Prefs.vibrate(this)
        engine = Prefs.engine(this)
        learnEnabled = Prefs.learn(this)
        torch = Torch(this) { on -> torchOn = on }
        calibration = CalibrationController(
            activity = this,
            currentParams = { params },
            hasPreset = { myPreset != null },
            onSavePreset = ::saveCalibratedPreset,
            onUseModel = ::useTrainedModel,
            onModelChanged = { m, e -> ModelStore.save(this, m, e) },
            onClose = { showCalibration = false },
        )
        reloadModelInfo()

        setContent {
            ChopTheme {
                BackHandler(enabled = showCalibration) { calibration.close() }
                AnimatedContent(
                    targetState = showCalibration,
                    transitionSpec = {
                        val dir = if (targetState) 1 else -1
                        (slideInHorizontally { it * dir / 4 } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it * dir / 4 } + fadeOut())
                    },
                    label = "screen",
                ) { cal ->
                    if (cal) CalibrationScreen(calibration) else ChopScreen(this@MainActivity)
                }
            }
        }

        if (enabled && !ChopService.running) ChopService.start(this)
    }

    override fun onStart() {
        super.onStart()
        torch.start()
        ChopService.debugListener = ::addLog
        ChopService.peakListener = { peak = it }
        ChopService.runningListener = { running = it; if (!it) peak = 0f }
        ChopService.listeningListener = { listening = it; if (!it) peak = 0f }
        running = ChopService.running
        listening = ChopService.listening
        batteryExempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        prefs.registerOnSharedPreferenceChangeListener(modelRevListener)
        strugglesToday = Prefs.struggles(this)
        engine = Prefs.engine(this)
        reloadAuto()
        reloadModelInfo()
    }

    override fun onStop() {
        prefs.unregisterOnSharedPreferenceChangeListener(modelRevListener)
        calibration.onHostStopped()
        ChopService.debugListener = null
        ChopService.peakListener = null
        ChopService.runningListener = null
        ChopService.listeningListener = null
        torch.stop()
        super.onStop()
    }

    override fun toggleGesture(on: Boolean) {
        enabled = on
        prefs.edit().putBoolean(Prefs.ENABLED, on).apply()
        if (on) {
            askNotificationPermission()
            ChopService.start(this)
        } else {
            ChopService.stop(this)
        }
    }

    override fun changeThreshold(t: Float) = applyParams(params.copy(threshold = t), log = false)

    override fun applyPreset(p: DetectorParams) {
        switchEngine(Engine.CLASSIC)
        applyParams(p, log = true)
    }

    override fun useAuto() {
        switchEngine(Engine.AUTO)
        addLog("auto: learning your force")
    }

    override fun useManual() {
        switchEngine(Engine.CLASSIC)
        addLog("manual: trigger %.1f m/s²".format(params.threshold))
    }

    override fun resetAuto() {
        Prefs.resetAuto(this)
        thread { com.kiro.choptorch.ml.SnippetStore.clear(ModelStore.dir(this)) }
        reloadAuto()
        // Restart the Auto engine so it forgets what it had in memory too.
        if (engine == Engine.AUTO) { switchEngine(Engine.CLASSIC); switchEngine(Engine.AUTO) }
        addLog("auto: started learning again from the default force")
    }

    private fun reloadAuto() {
        auto = AutoInfo(
            threshold = Prefs.autoThreshold(this),
            forces = Prefs.autoForces(this),
            gestures = Prefs.autoGestures(this),
            everyday = Prefs.autoEveryday(this),
            modelAt = Prefs.autoModelAt(this),
        )
    }

    override fun useModel() {
        if (model == null) return
        switchEngine(Engine.MODEL)
        addLog("using personal model")
    }

    override fun changeStrictness(s: Strictness) {
        model = model?.copy(strictness = s)
        thread { ModelStore.update(this) { m, _ -> m.strictness = s; m.retrain() } }
    }

    override fun changeLearn(on: Boolean) {
        learnEnabled = on
        prefs.edit().putBoolean(Prefs.LEARN, on).apply()
    }

    override fun undoLearning(sampleId: Long) {
        learnEvents = learnEvents.map { if (it.sampleId == sampleId) it.copy(undone = true) else it }
        thread {
            ModelStore.update(this) { m, log -> m.removeSample(sampleId); log.markUndone(sampleId) }
        }
        addLog("undid one learned sample")
    }

    override fun forgetLearned() {
        learnEvents = emptyList()
        thread { ModelStore.update(this) { m, log -> m.forgetLearned(); log.clear() } }
        addLog("forgot everything learned since calibration")
    }

    override fun openTest() {
        calibration.open()
        calibration.openTest(fromResult = false)
        showCalibration = true
    }

    private fun switchEngine(e: Engine) {
        engine = e
        Prefs.setEngine(this, e)
    }

    private fun useTrainedModel(m: PersonalModel) {
        model = ModelInfo.of(m)
        learnEvents = emptyList()
        Prefs.clearStruggles(this)
        strugglesToday = 0
        thread {
            ModelStore.save(this, m, clearLog = true)
            runOnUiThread {
                switchEngine(Engine.MODEL)
                addLog("trained personal model: " + m.summary())
            }
        }
    }

    private fun reloadModelInfo() {
        thread {
            val info = ModelStore.load(this)?.let(ModelInfo::of)
            val history = ModelStore.history(this)
            runOnUiThread {
                model = info
                learnEvents = history
                if (info == null && engine == Engine.MODEL) switchEngine(Engine.AUTO)
            }
        }
    }

    override fun saveCurrentAsPreset() {
        val note = "Saved by hand · trigger %.1f m/s²".format(params.threshold)
        Prefs.saveMyPreset(this, params, note)
        myPreset = params
        myPresetNote = note
        addLog("saved my preset: ${params.summary()}")
    }

    private fun saveCalibratedPreset(p: DetectorParams, note: String, switchToClassic: Boolean) {
        Prefs.saveMyPreset(this, p, note)
        myPreset = p
        myPresetNote = note
        applyParams(p, log = false)
        if (switchToClassic) switchEngine(Engine.CLASSIC)
        addLog("calibrated, my preset now: ${p.summary()}")
    }

    private fun applyParams(p: DetectorParams, log: Boolean) {
        params = p
        Prefs.saveParams(this, p)
        if (log) addLog("using " + p.summary())
    }

    override fun openCalibration() {
        calibration.open()
        showCalibration = true
    }

    override fun changeCooldown(sec: Float) {
        if (sec == cooldownSec) return
        cooldownSec = sec
        prefs.edit().putInt(Prefs.COOLDOWN, (sec * 1000).roundToInt()).apply()
    }

    override fun changeOnlyLocked(on: Boolean) {
        onlyLocked = on
        prefs.edit().putBoolean(Prefs.ONLY_LOCKED, on).apply()
    }

    override fun changeScreenOff(on: Boolean) {
        screenOff = on
        prefs.edit().putBoolean(Prefs.SCREEN_OFF, on).apply()
    }

    override fun changePocketGuard(on: Boolean) {
        pocketGuard = on
        prefs.edit().putBoolean(Prefs.POCKET_GUARD, on).apply()
    }

    override fun changeVibrate(on: Boolean) {
        vibrate = on
        prefs.edit().putBoolean(Prefs.VIBRATE, on).apply()
    }

    @SuppressLint("BatteryLife")
    override fun openBatterySettings() {
        val intent = if (!batteryExempt) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        runCatching { startActivity(intent) }
    }

    override fun applyRoot() {
        rootBusy = true
        addLog("running su commands…")
        thread {
            val out = RootHelper.applyKeepAlive(packageName)
            runOnUiThread {
                out.lines().filter { it.isNotBlank() }.forEach(::addLog)
                batteryExempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
                rootBusy = false
            }
        }
    }

    override fun testTorch() {
        addLog("manual toggle -> " + (torch.toggle()?.let { if (it) "ON" else "OFF" } ?: "failed"))
    }

    override fun clearLog() = log.clear()

    private fun addLog(line: String) {
        log.add(0, line)
        while (log.size > 40) log.removeAt(log.lastIndex)
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }
}
