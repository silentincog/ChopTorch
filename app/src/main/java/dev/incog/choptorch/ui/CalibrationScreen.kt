package dev.incog.choptorch.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SentimentDissatisfied
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import dev.incog.choptorch.DetectorParams
import dev.incog.choptorch.calib.CalResult
import dev.incog.choptorch.calib.Level
import dev.incog.choptorch.ml.ModelInfo
import dev.incog.choptorch.ml.Strictness
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checkroom
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Highlight
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import dev.incog.choptorch.calib.RepResult

enum class CalPhase { INTRO, RECORDING, NORMAL, ANALYSING, RESULT, TESTING }

/** One movement seen in Test & teach. taught: null = not corrected, true/false = taught as gesture or not. */
data class TestItem(
    val id: Int,
    val percent: Int,
    val matched: Boolean,
    val peak: Float,
    val gentle: Boolean,
    val taught: Boolean? = null,
)

/** What the user should do right now while recording. */
enum class Cue { GET_READY, NOW, REST, DONE }

/** Calibration state shown by [CalibrationScreen]. Implemented by CalibrationController. */
interface CalUi {
    val phase: CalPhase
    val target: Int
    val armed: Boolean
    val reps: Int
    val livePeak: Float
    val result: CalResult?
    val previous: DetectorParams
    val reportCopied: Boolean
    val cue: Cue
    val countdown: Int
    val recordingGesture: Boolean
    val level: Level
    val presetExists: Boolean
    val normalSeconds: Int
    val normalElapsedMs: Long
    val normalRecordedSeconds: Float
    val modelInfo: ModelInfo?
    val classicFalseTriggers: List<Int>?
    val testItems: List<TestItem>
    val lastTest: TestItem?
    val testFromResult: Boolean

    fun start()
    fun chooseLevel(l: Level)
    fun skipNormal()
    fun useModel()
    fun chooseStrictness(s: Strictness)
    fun openTest(fromResult: Boolean)
    fun teach(id: Int, wasGesture: Boolean)
    fun closeTest()
    fun finish()
    fun close()
    fun apply()
    fun copyReport()
}

@Composable
fun CalibrationScreen(cal: CalUi) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (cal.phase == CalPhase.TESTING) "Test & teach" else "Teach my gesture",
                        style = MaterialTheme.typography.titleLargeEmphasized,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (cal.phase == CalPhase.TESTING) cal.closeTest() else cal.close() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
    ) { inner ->
        val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        AnimatedContent(
            targetState = cal.phase,
            transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) },
            modifier = Modifier.padding(inner).fillMaxSize(),
            label = "phase",
        ) { phase ->
            when (phase) {
                CalPhase.INTRO -> Intro(cal)
                CalPhase.RECORDING -> RecordingView(cal)
                CalPhase.NORMAL -> NormalView(cal)
                CalPhase.ANALYSING -> Analysing()
                CalPhase.TESTING -> TestView(cal)
                CalPhase.RESULT -> cal.result?.let { ResultView(cal, it) } ?: Analysing()
            }
        }
    }
}

@Composable
private fun Intro(cal: CalUi) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                val spin = rememberInfiniteTransition(label = "spin")
                val angle by spin.animateFloat(
                    0f, 360f, infiniteRepeatable(tween(16_000, easing = LinearEasing), RepeatMode.Restart), label = "a",
                )
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(132.dp)) {
                    Box(
                        Modifier.size(132.dp).graphicsLayer { rotationZ = angle }
                            .clip(MaterialShapes.SoftBurst.toShape()).background(MaterialTheme.colorScheme.primary),
                    )
                    Icon(
                        Icons.Rounded.Vibration, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(52.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Teach it your gesture",
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "Chop Torch records your gesture and some everyday movement, then trains a small model on your phone that tells the two apart. Nothing leaves the phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        val steps = listOf(
            "Hold the phone the way you normally would",
            "Wait for the countdown. When it says Chop now and buzzes, do your gesture exactly how you want it to work. It repeats ${cal.target} times.",
            "Then use the phone normally for ${cal.normalSeconds} seconds without the gesture, so it learns what to ignore.",
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            steps.forEachIndexed { i, text ->
                SegmentedListItem(
                    shapes = ListItemDefaults.segmentedShapes(index = i, count = steps.size),
                    colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
                    leadingContent = { NumberBadge(i + 1) },
                ) { Text(text) }
            }
        }
        Text(
            "The torch stays off the whole time. Takes about a minute.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
        )
        Spacer(Modifier.height(4.dp))
        BigButton("Start", Icons.Rounded.PlayArrow, onClick = cal::start)
    }
}

@Composable
private fun RecordingView(cal: CalUi) {
    val shapes = remember {
        listOf(
            MaterialShapes.Circle, MaterialShapes.Cookie4Sided, MaterialShapes.Cookie6Sided,
            MaterialShapes.Cookie9Sided, MaterialShapes.Sunny, MaterialShapes.SoftBurst, MaterialShapes.Cookie12Sided,
        )
    }
    val reps = cal.reps
    val morph = remember(reps) { Morph(shapes[(reps - 1).coerceAtLeast(0) % shapes.size], shapes[reps % shapes.size]) }
    val progress = remember(reps) { Animatable(if (reps == 0) 1f else 0f) }
    val scale = remember { Animatable(1f) }
    val spatial = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(reps) {
        progress.animateTo(1f, spatial)
    }
    LaunchedEffect(reps) {
        if (reps > 0) {
            scale.snapTo(0.8f)
            scale.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 300f))
        }
    }
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(
        0f, 360f, infiniteRepeatable(tween(10_000, easing = LinearEasing), RepeatMode.Restart), label = "a",
    )
    val shapeColor by animateColorAsState(
        if (cal.armed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, label = "c",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(220.dp)) {
            Box(
                Modifier.size(220.dp)
                    .graphicsLayer { rotationZ = if (cal.armed) angle else 0f; scaleX = scale.value; scaleY = scale.value }
                    .clip(MorphShape(morph, progress.value))
                    .background(shapeColor),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "$reps",
                    style = MaterialTheme.typography.displayLargeEmphasized,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Text(
                    "of ${cal.target}",
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        CueBanner(cal)
        Spacer(Modifier.height(16.dp))
        RepDots(reps, cal.target)
        Spacer(Modifier.height(24.dp))
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Live force", style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.weight(1f))
                    Text(
                        "%.1f".format(cal.livePeak),
                        style = MaterialTheme.typography.headlineMediumEmphasized,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(10.dp))
                LinearWavyProgressIndicator(
                    progress = { (cal.livePeak / 40f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(14.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = cal::close,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.weight(1f).height(ButtonDefaults.MediumContainerHeight),
            ) { Text("Cancel", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight)) }
            Button(
                onClick = cal::finish,
                enabled = reps >= 3,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.weight(1f).height(ButtonDefaults.MediumContainerHeight),
            ) { Text("Finish", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight)) }
        }
        Text(
            "Finish unlocks after 3 gestures; more gestures make a better model. It moves on by itself after ${cal.target}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun CueBanner(cal: CalUi) {
    val (text, color) = when {
        cal.reps >= cal.target || cal.cue == Cue.DONE -> "That's all of them" to MaterialTheme.colorScheme.onSurface
        cal.recordingGesture && cal.cue != Cue.REST -> "Recording… hold still when done" to MaterialTheme.colorScheme.primary
        cal.cue == Cue.NOW -> "Chop now!" to MaterialTheme.colorScheme.tertiary
        cal.cue == Cue.REST -> "Got it. Rest a moment" to MaterialTheme.colorScheme.primary
        else -> "Get ready… ${cal.countdown}" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val scale = remember { Animatable(1f) }
    LaunchedEffect(cal.cue, cal.countdown, cal.recordingGesture) {
        scale.snapTo(if (cal.cue == Cue.NOW && !cal.recordingGesture) 0.7f else 0.9f)
        scale.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 400f))
    }
    Text(
        text,
        style = if (cal.cue == Cue.NOW && !cal.recordingGesture) MaterialTheme.typography.displaySmallEmphasized
        else MaterialTheme.typography.headlineSmallEmphasized,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value },
    )
}

@Composable
private fun RepDots(reps: Int, target: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(target) { i ->
            val done = i < reps
            val color by animateColorAsState(
                if (done) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant, label = "dot",
            )
            Box(
                Modifier.size(if (done) 22.dp else 16.dp)
                    .clip(if (done) MaterialShapes.Cookie4Sided.toShape() else CircleShape)
                    .background(color),
            )
        }
    }
}

@Composable
private fun Analysing() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(24.dp),
    ) {
        LoadingIndicator(Modifier.size(96.dp))
        Spacer(Modifier.height(16.dp))
        Text("Testing every sensitivity on your gestures…", style = MaterialTheme.typography.titleMediumEmphasized, textAlign = TextAlign.Center)
    }
}

@Composable
private fun NormalView(cal: CalUi) {
    val total = cal.normalSeconds * 1000f
    val elapsed = cal.normalElapsedMs.toFloat().coerceAtMost(total)
    val left = ((total - elapsed) / 1000f).toInt().coerceAtLeast(0)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
            CircularWavyProgressIndicator(
                progress = { elapsed / total },
                modifier = Modifier.size(200.dp),
                stroke = Stroke(width = 14f * 2),
                trackStroke = Stroke(width = 14f * 2),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$left", style = MaterialTheme.typography.displayLargeEmphasized, color = MaterialTheme.colorScheme.primary)
                Text("seconds", style = MaterialTheme.typography.titleMediumEmphasized)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("Now use your phone normally", style = MaterialTheme.typography.headlineSmallEmphasized, textAlign = TextAlign.Center)
        Text(
            "Don't do the gesture. This teaches the model what to ignore.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        val ideas = listOf(
            Icons.AutoMirrored.Rounded.DirectionsWalk to "Walk a few steps",
            Icons.Rounded.PanTool to "Pick it up and put it down",
            Icons.Rounded.Checkroom to "Put it in your pocket and take it out",
            Icons.Rounded.Swipe to "Wave it around casually",
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ideas.forEachIndexed { i, (icon, text) ->
                SegmentedListItem(
                    shapes = ListItemDefaults.segmentedShapes(index = i, count = ideas.size),
                    colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
                    leadingContent = { StatusBadge(icon, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer) },
                ) { Text(text) }
            }
        }
        Spacer(Modifier.height(20.dp))
        val enough = elapsed >= 10_000f
        FilledTonalButton(
            onClick = cal::skipNormal,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight),
        ) { Text(if (enough) "Finish now" else "Skip this step", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight)) }
        if (!enough) {
            Text(
                "Skipping works, but the model then has less to learn what to ignore.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ResultView(cal: CalUi, r: CalResult) {
    val model = cal.modelInfo
    val classicFa = cal.classicFalseTriggers?.getOrNull(cal.level.ordinal)
    val classicOk = r.paramsFor(cal.level) != null
    val modelFirst = model != null && (!classicOk ||
        (model.recognised >= r.recognised && (classicFa == null || model.falseTriggers <= classicFa)))
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { ResultHeader(r, model != null) }
        if (modelFirst) {
            item { ModelCard(cal, model!!, recommended = true) }
            if (classicOk) item { ClassicCard(cal, r, classicFa, recommended = false) }
        } else {
            if (classicOk) item { ClassicCard(cal, r, classicFa, recommended = model == null) }
            if (model != null) item { ModelCard(cal, model, recommended = false) }
        }
        if (r.warnings.isNotEmpty()) item { WarningsCard(r.warnings) }
        if (r.reps.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Your gestures (classic check)",
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 6.dp),
                    )
                    r.reps.forEachIndexed { i, rep -> RepRow(rep, i, r.reps.size) }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilledTonalButton(
                        onClick = cal::start,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.weight(1f).height(ButtonDefaults.MediumContainerHeight),
                    ) {
                        Icon(Icons.Rounded.Replay, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Try again")
                    }
                    OutlinedButton(
                        onClick = cal::copyReport,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.weight(1f).height(ButtonDefaults.MediumContainerHeight),
                    ) {
                        Icon(if (cal.reportCopied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (cal.reportCopied) "Copied" else "Copy report")
                    }
                }
                Text(
                    "Paste the report into the Kiro chat if you want it tuned further.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun ResultHeader(r: CalResult, hasModel: Boolean) {
    val ok = hasModel || r.params != null
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val on = if (ok) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(56.dp)
                    .clip((if (ok) MaterialShapes.Cookie9Sided else MaterialShapes.Puffy).toShape())
                    .background(if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error),
            ) {
                Icon(
                    if (ok) Icons.Rounded.Check else Icons.Rounded.SentimentDissatisfied,
                    contentDescription = null,
                    tint = if (ok) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onError,
                    modifier = Modifier.size(30.dp),
                )
            }
            Column(Modifier.padding(start = 16.dp)) {
                Text(
                    when {
                        hasModel -> "Your gesture is learned"
                        r.params != null -> r.headline
                        else -> r.headline
                    },
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    color = on,
                )
                if (!r.averagePeak.isNaN() && r.counted > 0) {
                    Text(
                        "${r.reps.size} gestures · average force %.1f m/s²".format(r.averagePeak),
                        style = MaterialTheme.typography.bodyMedium,
                        color = on,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelCard(cal: CalUi, m: ModelInfo, recommended: Boolean) {
    OptionCard(
        title = "Personal model",
        subtitle = "Learned from your ${m.positives} gestures and ${m.negatives} everyday movements",
        recommended = recommended,
        icon = Icons.Rounded.Psychology,
    ) {
        StatLine(
            "${m.recognised} of ${m.stats.positives} of your gestures recognised",
            "Each one checked against the others only, like a new gesture would be.",
            ok = m.recognised == m.stats.positives,
        )
        if (m.stats.normalWindows > 0) {
            StatLine(
                "${m.falseTriggers} false triggers in %.0f s of normal use".format(m.stats.normalSeconds),
                null,
                ok = m.falseTriggers == 0,
            )
        } else {
            StatLine("No normal-use recording", "It still knows walking and running, but can't check your own handling.", ok = false)
        }
        ConnectedPicker(
            items = Strictness.entries,
            selected = m.strictness,
            label = { it.label },
            onPick = cal::chooseStrictness,
        )
        Text(
            m.strictness.hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(14.dp))
        BigButton(if (recommended) "Use my model" else "Use the model instead", Icons.Rounded.Done, onClick = cal::useModel)
        TextButton(onClick = { cal.openTest(true) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Science, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Test it first, and correct mistakes")
        }
    }
}

@Composable
private fun ClassicCard(cal: CalUi, r: CalResult, falseTriggers: Int?, recommended: Boolean) {
    val p = r.paramsFor(cal.level) ?: return
    OptionCard(
        title = "Classic detector",
        subtitle = "Fixed double-chop rules with a trigger force from your average",
        recommended = recommended,
        icon = Icons.Rounded.Tune,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("%.1f".format(p.threshold), style = MaterialTheme.typography.displaySmallEmphasized, color = MaterialTheme.colorScheme.primary)
            Text(
                " m/s² · was %.1f".format(cal.previous.threshold),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        StatLine("${r.recognised} of ${r.counted} gestures recognised", null, ok = r.recognised == r.counted)
        if (falseTriggers != null) {
            StatLine("$falseTriggers false triggers in %.0f s of normal use".format(cal.normalRecordedSeconds), null, ok = falseTriggers == 0)
        }
        if (r.levels.map { it.threshold }.distinct().size > 1) {
            ConnectedPicker(items = Level.entries, selected = cal.level, label = { it.label }, onPick = cal::chooseLevel)
            Text(
                cal.level.hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        if (recommended) {
            BigButton(if (cal.presetExists) "Replace my preset and use it" else "Save as my preset", Icons.Rounded.Done, onClick = cal::apply)
        } else {
            OutlinedButton(
                onClick = cal::apply,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight),
            ) { Text("Use classic instead") }
        }
    }
}

@Composable
private fun OptionCard(
    title: String,
    subtitle: String,
    recommended: Boolean,
    icon: ImageVector,
    content: @Composable () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (recommended) MaterialTheme.colorScheme.surfaceBright else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(
                    icon,
                    if (recommended) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                    if (recommended) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (recommended) Pill("Recommended")
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun StatLine(text: String, detail: String?, ok: Boolean) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Icon(
            if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Info,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.padding(start = 8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun <T> ConnectedPicker(items: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        items.forEachIndexed { i, item ->
            ToggleButton(
                checked = item == selected,
                onCheckedChange = { onPick(item) },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    items.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
            ) { Text(label(item), maxLines = 1) }
        }
    }
}

@Composable
private fun WarningsCard(warnings: List<String>) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            warnings.forEach { w ->
                Row {
                    Icon(
                        Icons.Rounded.Info, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(18.dp).padding(top = 2.dp),
                    )
                    Text(
                        w, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TestView(cal: CalUi) {
    val last = cal.lastTest
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { TestMeter(cal, last) }
        cal.modelInfo?.let { m ->
            item {
                Card(
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Strictness", style = MaterialTheme.typography.titleMediumEmphasized)
                        ConnectedPicker(Strictness.entries, m.strictness, { it.label }, cal::chooseStrictness)
                        Text(
                            "Model: ${m.positives} gestures, ${m.negatives} non-gestures · recognises ${m.recognised}/${m.stats.positives} of your samples",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
        item {
            Text(
                if (cal.testItems.isEmpty()) "Do your gesture, or move the phone around. Each movement shows up here."
                else "Recent movements. Correct any mistake and the model learns it right away.",
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp),
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                cal.testItems.forEachIndexed { i, t -> TestRow(cal, t, i, cal.testItems.size) }
            }
        }
        item {
            BigButton(if (cal.testFromResult) "Back to results" else "Done", Icons.Rounded.Done, onClick = cal::closeTest)
        }
    }
}

@Composable
private fun TestMeter(cal: CalUi, last: TestItem?) {
    val matched = last?.matched == true
    val bg by animateColorAsState(
        if (matched) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer, label = "bg",
    )
    val scale = remember { Animatable(1f) }
    LaunchedEffect(last?.id) {
        if (last != null) {
            scale.snapTo(0.85f)
            scale.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 350f))
        }
    }
    Card(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = bg), modifier = Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(120.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                    .clip((if (matched) MaterialShapes.Sunny else MaterialShapes.Cookie9Sided).toShape())
                    .background(if (matched) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary),
            ) {
                Text(
                    last?.let { if (it.gentle) "–" else "${it.percent}%" } ?: "…",
                    style = MaterialTheme.typography.headlineLargeEmphasized,
                    color = if (matched) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                when {
                    last == null -> "Waiting for a movement"
                    matched -> "Match: the torch would switch"
                    last.gentle -> "Too gentle to count"
                    else -> "No match"
                },
                style = MaterialTheme.typography.headlineSmallEmphasized,
                textAlign = TextAlign.Center,
            )
            Text(
                "Above 50% switches the torch. The torch stays off while testing.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            LinearWavyProgressIndicator(
                progress = { (last?.percent ?: 0) / 100f },
                modifier = Modifier.fillMaxWidth().height(14.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("0%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                Text("50%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Text("100%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
            Text(
                "Live force %.1f m/s²".format(cal.livePeak),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun TestRow(cal: CalUi, t: TestItem, index: Int, count: Int) {
    val (icon, bg, fg) = when {
        t.matched -> Triple(Icons.Rounded.Highlight, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary)
        t.gentle -> Triple(Icons.Rounded.Remove, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Triple(Icons.Rounded.Close, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
    }
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        leadingContent = { StatusBadge(icon, bg, fg) },
        supportingContent = {
            Text(
                when (t.taught) {
                    true -> "Learned as your gesture"
                    false -> "Learned as not your gesture"
                    null -> "Peak %.1f m/s²".format(t.peak)
                },
            )
        },
        trailingContent = {
            if (t.taught == null) {
                TextButton(onClick = { cal.teach(t.id, !t.matched) }) {
                    Text(if (t.matched) "Wasn't me" else "That was it")
                }
            } else {
                Icon(Icons.Rounded.Check, contentDescription = "Learned", tint = MaterialTheme.colorScheme.primary)
            }
        },
    ) {
        Text(
            when {
                t.matched -> "Match ${t.percent}% · would switch"
                t.gentle -> "Too gentle"
                else -> "No match · ${t.percent}%"
            },
        )
    }
}

@Composable
private fun RepRow(rep: RepResult, index: Int, count: Int) {
    val (icon, bg, fg) = when {
        rep.ignored -> Triple(Icons.Rounded.Remove, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        rep.recognised -> Triple(Icons.Rounded.Check, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
        else -> Triple(Icons.Rounded.Close, MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError)
    }
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        leadingContent = { StatusBadge(icon, bg, fg) },
        supportingContent = { Text(rep.detail) },
    ) { Text("Gesture ${rep.number} · peak %.1f m/s²".format(rep.peak)) }
}

@Composable
private fun StatusBadge(icon: ImageVector, bg: Color, fg: Color) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(40.dp).clip(MaterialShapes.Cookie4Sided.toShape()).background(bg),
    ) { Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun NumberBadge(n: Int) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(40.dp).clip(MaterialShapes.Cookie6Sided.toShape()).background(MaterialTheme.colorScheme.primary),
    ) { Text("$n", style = MaterialTheme.typography.titleMediumEmphasized, color = MaterialTheme.colorScheme.onPrimary) }
}

@Composable
private fun Pill(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun BigButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    val h = ButtonDefaults.MediumContainerHeight
    Button(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        contentPadding = ButtonDefaults.contentPaddingFor(h),
        modifier = Modifier.fillMaxWidth().height(h),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(h)))
        Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(h)))
        Text(label, style = ButtonDefaults.textStyleFor(h))
    }
}
