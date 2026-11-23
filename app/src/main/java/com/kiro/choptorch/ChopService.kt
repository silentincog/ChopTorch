package com.kiro.choptorch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.kiro.choptorch.ml.AutoModelBuilder
import com.kiro.choptorch.ml.AutoPilot
import com.kiro.choptorch.ml.LearnEvent
import com.kiro.choptorch.ml.Snippet
import com.kiro.choptorch.ml.SnippetStore
import com.kiro.choptorch.ml.ModelStore
import com.kiro.choptorch.ml.OnlineLearner
import com.kiro.choptorch.ml.Origin
import com.kiro.choptorch.ml.PersonalDetector

class ChopService : Service(), SensorEventListener {

    companion object {
        private const val CHANNEL = "chop"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "com.kiro.choptorch.STOP"
        const val ACTION_WRONG = "com.kiro.choptorch.WRONG"
        private const val SELF_CHANGE_MS = 1500L
        private const val SAMPLE_US = 10_000 // 100 Hz

        @Volatile var running = false
            private set

        /** Live debug feed for the UI (called on the main thread). */
        @Volatile var debugListener: ((String) -> Unit)? = null
        @Volatile var peakListener: ((Float) -> Unit)? = null
        @Volatile var runningListener: ((Boolean) -> Unit)? = null

        /** True while the accelerometer is on (false while paused, e.g. phone unlocked). */
        @Volatile var listening = false
            private set
        @Volatile var listeningListener: ((Boolean) -> Unit)? = null

        /** Set while calibrating so practice gestures don't switch the torch. */
        @Volatile var suppressed = false

        fun start(ctx: Context) {
            val i = Intent(ctx, ChopService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ChopService::class.java))
        }
    }

    private lateinit var sm: SensorManager
    private lateinit var torch: Torch
    /** Swapped on the main thread, read on the sensor thread. */
    @Volatile private var engine: GestureEngine = ChopDetector(DetectorParams(), {})
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var sensorThread: HandlerThread
    private lateinit var sensorHandler: Handler
    private val main = Handler(Looper.getMainLooper())

    // Learning from use. The learner lives on the main thread; model updates run on learnExec.
    private val learnExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    private lateinit var learner: OnlineLearner
    /** AUTO_CONFIRMED sample ids by window onset, so a later "Wasn't me" can take them back. learnExec only. */
    private val confirmedIds = HashMap<Long, Long>()
    private var lastMatch: PersonalDetector.Result? = null
    private var selfChangeAt = Long.MIN_VALUE / 2
    private var selfChangeTo = false
    private var gestureTurnedOn = false
    private var ignoreTorchUntil = 0L

    private var accel: Sensor? = null
    private var proximity: Sensor? = null
    private var accelRegistered = false
    private var proximityRegistered = false
    @Volatile private var inPocket = false
    private var screenOn = true
    private lateinit var keyguard: android.app.KeyguardManager
    /** Sensor timestamps minus elapsedRealtimeNanos, so torch events can be put on the sensor clock. */
    @Volatile private var sensorClockOffsetNs = 0L

    private val recheckLock = Runnable { applyScreenState() }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            in Prefs.DETECTOR_KEYS, Prefs.ENGINE, Prefs.MODEL_REV -> rebuildEngine()
            Prefs.LEARN -> learner.enabled = Prefs.learn(this)
            Prefs.SCREEN_OFF, Prefs.POCKET_GUARD, Prefs.ONLY_LOCKED -> applyScreenState()
            Prefs.COOLDOWN -> engine.cooldownMs = Prefs.cooldownMs(this)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> screenOn = false
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    // Fingerprint or face unlock can dismiss the lock screen just after the screen
                    // turns on; check again shortly in case USER_PRESENT is late or missing.
                    main.removeCallbacks(recheckLock)
                    main.postDelayed(recheckLock, 400)
                    main.postDelayed(recheckLock, 1500)
                }
                Intent.ACTION_USER_PRESENT -> screenOn = true
            }
            applyScreenState()
        }
    }

    private val peakTicker = object : Runnable {
        override fun run() {
            peakListener?.invoke(engine.takePeak())
            learner.tick(SystemClock.elapsedRealtime())
            main.postDelayed(this, 100)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sm = getSystemService(SensorManager::class.java)
        learner = OnlineLearner(
            learn = ::applyLesson,
            judge = { (engine as? PersonalDetector)?.model },
            log = ::post,
            onStruggle = { Prefs.addStruggle(this); post("missed you; you used the torch by hand") },
        )
            .also { it.enabled = Prefs.learn(this) }
        // Registering the torch callback reports the current state once; that is not a user action.
        ignoreTorchUntil = SystemClock.elapsedRealtime() + 1000
        torch = Torch(this, ::onTorchChanged).also { it.start() }
        rebuildEngine()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChopTorch:sensor")
            .apply { setReferenceCounted(false) }
        sensorThread = HandlerThread("chop-sensor").also { it.start() }
        sensorHandler = Handler(sensorThread.looper)

        accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        proximity = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        startForegroundCompat()
        running = true
        runningListener?.invoke(true)

        keyguard = getSystemService(android.app.KeyguardManager::class.java)
        screenOn = getSystemService(PowerManager::class.java).isInteractive
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        Prefs.get(this).registerOnSharedPreferenceChangeListener(prefListener)
        applyScreenState()
        main.post(peakTicker)

        if (accel == null) post("No accelerometer found!")
        if (!torch.available) post("No flash unit found!")
        post("service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.get(this).edit().putBoolean(Prefs.ENABLED, false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_WRONG) onWrong()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        runningListener?.invoke(false)
        setListening(false)
        main.removeCallbacks(peakTicker)
        main.removeCallbacks(recheckLock)
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        runCatching { unregisterReceiver(screenReceiver) }
        sm.unregisterListener(this)
        accelRegistered = false
        proximityRegistered = false
        if (wakeLock.isHeld) wakeLock.release()
        torch.stop()
        sensorThread.quitSafely()
        learnExec.shutdown()
        post("service stopped")
        super.onDestroy()
    }

    /**
     * Decides whether to listen (see [ListenPolicy]). With "Only when locked", the sensor is off
     * while you use the phone, so it can't trigger and costs no battery then. With the screen off
     * it holds a partial wake lock, because the accelerometer stops reporting when the CPU sleeps.
     */
    private fun applyScreenState() {
        val locked = screenOn && keyguard.isKeyguardLocked
        val listen = accel != null && ListenPolicy.shouldListen(screenOn, locked, Prefs.onlyLocked(this), Prefs.screenOff(this))
        if (listen && !accelRegistered) {
            accelRegistered = sm.registerListener(this, accel, SAMPLE_US, sensorHandler)
            sensorHandler.post { engine.restart() }
        } else if (!listen && accelRegistered) {
            sm.unregisterListener(this, accel)
            accelRegistered = false
        }
        setListening(accelRegistered)

        val guard = !screenOn && listen && Prefs.pocketGuard(this) && proximity != null
        if (guard && !proximityRegistered) {
            proximityRegistered = sm.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler)
        } else if (!guard && proximityRegistered) {
            sm.unregisterListener(this, proximity)
            proximityRegistered = false
            inPocket = false
        }

        if (!screenOn && listen) {
            if (!wakeLock.isHeld) wakeLock.acquire()
        } else if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    private fun setListening(on: Boolean) {
        if (listening == on) return
        listening = on
        post(if (on) "listening" else "paused (phone unlocked)")
        main.post { listeningListener?.invoke(on) }
        updateNotification()
    }

    /** Now, on the sensor clock. */
    private fun sensorNow(): Long = SystemClock.elapsedRealtimeNanos() + sensorClockOffsetNs

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                sensorClockOffsetNs = e.timestamp - SystemClock.elapsedRealtimeNanos()
                engine.onSample(e.values[0], e.values[1], e.values[2], e.timestamp)
            }
            Sensor.TYPE_PROXIMITY -> inPocket = e.values[0] < (e.sensor.maximumRange.coerceAtMost(5f))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Builds the engine the user picked; falls back to the classic detector if no model exists.
     * The model loads on learnExec, so it never races a lesson being saved.
     */
    private fun rebuildEngine() {
        when (Prefs.engine(this)) {
            Engine.CLASSIC -> { useClassic(); return }
            Engine.AUTO -> { if (engine !is AutoPilot) useAuto(); return }
            Engine.MODEL -> Unit
        }
        learnExec.execute {
            val m = ModelStore.load(this)
            main.post {
                if (Prefs.engine(this) != Engine.MODEL) return@post
                val cur = engine
                when {
                    m == null -> useClassic()
                    cur is PersonalDetector && cur.compatible(m) -> {
                        cur.model = m // keeps the live signal pipeline running
                        post("model updated: " + m.summary())
                    }
                    else -> {
                        post("using " + m.summary())
                        engine = PersonalDetector(m, ::onDoubleChop, onResult = ::onModelResult)
                            .also { it.cooldownMs = Prefs.cooldownMs(this) }
                    }
                }
            }
        }
    }

    private fun useAuto() {
        val p = Prefs.params(this)
        val t = Prefs.autoThreshold(this)
        post("using auto: learning your force, trigger %.1f m/s²".format(t))
        engine = AutoPilot(p, t, Prefs.autoForces(this), Prefs.autoFalse(this), autoCallbacks())
            .also { it.cooldownMs = Prefs.cooldownMs(this) }
    }

    private fun autoCallbacks() = object : AutoPilot.Callbacks {
            override fun trigger() = onDoubleChop()
            override fun thresholdChanged(threshold: Float, forces: List<Float>, falseForces: List<Float>) =
                Prefs.saveAuto(this@ChopService, threshold, forces, falseForces)
            override fun gestureCollected(s: Snippet) = learnExec.execute { collectGesture(s) }
            override fun everydayCollected(s: Snippet) = learnExec.execute {
                SnippetStore.addEveryday(ModelStore.dir(this@ChopService), s)
                val pr = Prefs.get(this@ChopService)
                pr.edit().putInt(Prefs.AUTO_EVERYDAY, minOf(SnippetStore.MAX_EVERYDAY, pr.getInt(Prefs.AUTO_EVERYDAY, 0) + 1)).apply()
            }
            override fun falseCollected(s: Snippet) = learnExec.execute { SnippetStore.addFalse(ModelStore.dir(this@ChopService), s) }
            override fun log(msg: String) = post(msg)
    }

    /** learnExec: keep a confirmed gesture; with enough of them, train the personal model and switch to it. */
    private fun collectGesture(s: Snippet) {
        val dir = ModelStore.dir(this)
        val n = SnippetStore.addGesture(dir, s)
        Prefs.get(this).edit().putInt(Prefs.AUTO_GESTURES, n).apply()
        if (n < AutoModelBuilder.MIN_GESTURES || Prefs.engine(this) != Engine.AUTO) return
        val c = SnippetStore.load(dir)
        val r = AutoModelBuilder.build(c.gestures, c.everyday, c.falseTriggers)
        val model = r.model
        if (model == null) {
            post("auto: $n gestures collected; not ready for a model yet (${r.reason}), collecting more")
            return
        }
        ModelStore.save(this, model, clearLog = true)
        SnippetStore.clear(dir)
        Prefs.get(this).edit().putLong(Prefs.AUTO_MODEL_AT, System.currentTimeMillis()).putInt(Prefs.AUTO_GESTURES, 0).apply()
        Prefs.setEngine(this, Engine.MODEL)
        post("auto: personal model trained from ${r.used} of your gestures (${r.outliers} left out); switched to it. " + model.summary())
    }

    private fun useClassic() {
        val p = Prefs.params(this)
        post("using classic: " + p.summary())
        engine = ChopDetector(p, ::onDoubleChop) { e -> post(e.text()) }
            .also { it.cooldownMs = Prefs.cooldownMs(this) }
    }

    /** Sensor thread. */
    private fun onModelResult(r: PersonalDetector.Result) {
        if (r.matched || r.percent >= 25) {
            post("model: %s %d%% match (peak %.1f)".format(if (r.matched) "TORCH," else "no,", r.percent, r.peak))
        }
        main.post {
            if (suppressed) return@post
            if (r.matched) lastMatch = r else learner.onNoMatch(r, SystemClock.elapsedRealtime())
        }
    }

    private fun onDoubleChop() {
        if (suppressed) {
            post("ignored: calibrating")
            return
        }
        if (inPocket) {
            post("ignored: proximity covered (pocket guard)")
            return
        }
        main.post {
            selfChangeAt = SystemClock.elapsedRealtime()
            selfChangeTo = !torch.isOn
            val state = torch.toggle()
            if (state == null) {
                post("torch toggle failed (camera busy?)")
            } else {
                post("torch " + if (state) "ON" else "OFF")
                if (Prefs.vibrate(this)) buzz(state)
                val r = lastMatch
                val e = engine
                if (e is PersonalDetector && r != null) {
                    learner.onTriggered(r, selfChangeAt)
                    gestureTurnedOn = state
                    updateNotification()
                } else if (e is AutoPilot) {
                    val t = sensorNow()
                    sensorHandler.post { e.onToggled(t) }
                    gestureTurnedOn = state
                    updateNotification()
                }
                lastMatch = null
            }
        }
    }

    /** Main thread: every torch change, ours or not. */
    private fun onTorchChanged(on: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!on) gestureTurnedOn = false
        val ours = on == selfChangeTo && now - selfChangeAt < SELF_CHANGE_MS
        val cameraTookIt = now - torch.unavailableAt < SELF_CHANGE_MS
        if (!ours && !cameraTookIt && now >= ignoreTorchUntil && !suppressed) {
            when (val e = engine) {
                is PersonalDetector -> learner.onManualTorchChange(now)
                is AutoPilot -> { val t = sensorNow(); sensorHandler.post { e.onManualTorchChange(t) } }
                else -> Unit
            }
        }
        updateNotification()
    }

    /** "Wasn't me" from the notification. */
    private fun onWrong() {
        val now = SystemClock.elapsedRealtime()
        when (val e = engine) {
            is PersonalDetector -> if (learner.onWrong(now)) post("you said that trigger was wrong; learning from it")
            is AutoPilot -> { val t = sensorNow(); sensorHandler.post { e.onWrong(t) } }
            else -> Unit
        }
        if (torch.isOn) {
            selfChangeAt = now
            selfChangeTo = false
            torch.set(false)
        }
        gestureTurnedOn = false
        updateNotification()
    }

    /** learnExec: apply one lesson to the stored model; the MODEL_REV bump then reloads it here and in the UI. */
    private fun applyLesson(l: OnlineLearner.Lesson) {
        learnExec.execute {
            val id = ModelStore.update(this) { m, log ->
                if (!l.positive) {
                    confirmedIds.remove(l.key)?.let { old -> if (m.removeSample(old)) log.markUndone(old) }
                }
                val id = if (l.positive) m.addPositive(l.window, l.peak, l.origin) else m.addNegative(l.window, l.origin)
                log.add(LearnEvent(id, System.currentTimeMillis(), l.positive, l.origin, l.percent))
                id
            } ?: return@execute
            if (l.origin == Origin.AUTO_CONFIRMED) confirmedIds[l.key] = id
            post("learned %s: %s (%d%% match)".format(if (l.positive) "your gesture" else "not your gesture", l.origin.reason.lowercase(), l.percent))
        }
    }

    private fun buzz(on: Boolean) {
        val v = getSystemService(Vibrator::class.java) ?: return
        val effect = if (on) VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
        else VibrationEffect.createWaveform(longArrayOf(0, 40, 60, 40), -1)
        v.vibrate(effect)
    }

    private fun post(msg: String) {
        Log.d("ChopTorch", msg)
        val stamp = "%.1f".format(SystemClock.elapsedRealtime() / 1000.0 % 1000)
        main.post { debugListener?.invoke("[$stamp] $msg") }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ChopService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val wrong = gestureTurnedOn && torch.isOn && (engine is PersonalDetector || engine is AutoPilot)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_torch)
            .setContentTitle(if (wrong) "Torch turned on by your gesture" else "Chop chop for flashlight")
            .setContentText(
                when {
                    wrong -> "Not on purpose? Tap Wasn't me and it learns."
                    !listening && Prefs.onlyLocked(this) -> "Paused while unlocked. Works on the lock screen and with the screen off."
                    else -> "Do your gesture to toggle the torch"
                },
            )
            .setContentIntent(open)
            .setOngoing(true)
        if (wrong) {
            val w = PendingIntent.getService(
                this, 2, Intent(this, ChopService::class.java).setAction(ACTION_WRONG), PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(Notification.Action.Builder(null, "Wasn't me", w).build())
        }
        b.addAction(Notification.Action.Builder(null, "Stop", stop).build())
        return b.build()
    }

    private fun updateNotification() {
        if (!running) return
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification()) }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Chop gesture", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            }
        )
        val n = notification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            Log.e("ChopTorch", "startForeground failed", e)
        }
    }
}
