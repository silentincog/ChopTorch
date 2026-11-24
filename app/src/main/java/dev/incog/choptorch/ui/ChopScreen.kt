package dev.incog.choptorch.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.activity.compose.BackHandler
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import dev.incog.choptorch.Buzz
import dev.incog.choptorch.VibrationPattern
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import dev.incog.choptorch.ChopDetector
import dev.incog.choptorch.Strength
import dev.incog.choptorch.DetectorParams
import dev.incog.choptorch.Engine
import dev.incog.choptorch.ml.LearnEvent
import dev.incog.choptorch.ml.ModelInfo
import dev.incog.choptorch.ml.Origin
import androidx.compose.material.icons.rounded.AddTask
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.incog.choptorch.ml.Strictness
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.OutlinedButton
import dev.incog.choptorch.calib.Calibrator
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Highlight
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonSize
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph

/** Everything the screen shows. Owned by MainActivity, backed by Compose snapshot state. */
interface ChopUi {
    val enabled: Boolean
    val running: Boolean
    /** The service is running but has the sensor off (phone unlocked with "Only when locked"). */
    val listening: Boolean
    val onlyLocked: Boolean
    /** Delay between toggles, in seconds. */
    val cooldownSec: Float
    val torchOn: Boolean
    val threshold: Float
    val params: DetectorParams
    val myPreset: DetectorParams?
    val myPresetNote: String
    val engine: Engine
    val auto: AutoInfo
    val model: ModelInfo?
    val learnEnabled: Boolean
    /** Newest first. */
    val learnEvents: List<LearnEvent>
    /** Times in the last day you used the torch by hand after it missed you. */
    val strugglesToday: Int
    val screenOff: Boolean
    val pocketGuard: Boolean
    val vibrate: Boolean
    val vibrationStrength: Strength
    /** Your own timings, used when [vibrationStrength] is Custom. */
    val customPattern: VibrationPattern
    /** What actually plays: the preset, or [customPattern]. */
    val vibrationPattern: VibrationPattern
    /** The motor can vary its strength; otherwise only length matters. */
    val hasAmplitudeControl: Boolean
    val theme: ThemeSettings
    val appVersion: String
    /** Chop Torch 3.x (com.kiro.choptorch) is still installed. */
    val oldAppInstalled: Boolean
    val importBusy: Boolean
    val importMessage: String?
    val batteryExempt: Boolean
    val rootBusy: Boolean
    val peak: Float
    val log: List<String>

    fun toggleGesture(on: Boolean)
    fun changeThreshold(t: Float)
    fun changeScreenOff(on: Boolean)
    fun changeOnlyLocked(on: Boolean)
    fun changeCooldown(sec: Float)
    fun changePocketGuard(on: Boolean)
    fun changeVibrate(on: Boolean)
    /** Saves the strength and plays it once, so you feel the difference. */
    fun changeVibrationStrength(s: Strength)
    /** Plays the on buzz, then the off buzz. */
    fun testVibration()
    /** Saves custom timings; plays [preview] with them if given. */
    fun changeCustomPattern(p: VibrationPattern, preview: Buzz?)
    fun changeTheme(t: ThemeSettings)
    fun importFromOldApp()
    fun uninstallOldApp()
    fun openReleases()
    fun openBatterySettings()
    fun applyRoot()
    fun testTorch()
    fun clearLog()
    fun applyPreset(p: DetectorParams)
    fun saveCurrentAsPreset()
    fun openCalibration()
    fun useModel()
    fun useAuto()
    fun useManual()
    fun resetAuto()
    fun changeStrictness(s: Strictness)
    fun openTest()
    fun changeLearn(on: Boolean)
    fun undoLearning(sampleId: Long)
    fun forgetLearned()
}

private const val T_MIN = Calibrator.SLIDER_MIN
private const val T_MAX = Calibrator.SLIDER_MAX

/** Moves the trigger force one 0.5 m/s² step, snapping odd values onto the 0.5 grid first. */
private fun stepThreshold(t: Float, dir: Int): Float {
    val halfSteps = (t * 2f).roundToInt() + dir
    return (halfSteps / 2f).coerceIn(T_MIN, T_MAX)
}

/** The four tabs of the main screen. */
enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME("Home", Icons.Outlined.Home, Icons.Rounded.Home),
    GESTURE("Gesture", Icons.Outlined.Gesture, Icons.Rounded.Gesture),
    VIBRATION("Vibration", Icons.Outlined.Vibration, Icons.Rounded.Vibration),
    SETTINGS("Settings", Icons.Outlined.Settings, Icons.Rounded.Settings),
}

@Composable
fun ChopScreen(ui: ChopUi, initialTab: Tab = Tab.HOME) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    BackHandler(enabled = page != null) { page = null }
    BackHandler(enabled = page == null && tab != Tab.HOME) { tab = Tab.HOME }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            AnimatedVisibility(page == null, enter = fadeIn(), exit = fadeOut()) {
                ShortNavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Tab.entries.forEach { t ->
                        ShortNavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(if (tab == t) t.selectedIcon else t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { outer ->
        val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        AnimatedContent(
            targetState = page to tab,
            transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) },
            modifier = Modifier.padding(bottom = outer.calculateBottomPadding()),
            label = "tab",
        ) { (pg, t) ->
            if (pg != null) {
                SettingsPageScreen(pg, ui, onBack = { page = null })
            } else {
                when (t) {
                    Tab.HOME -> TabScaffold("Chop Torch", statusLine(ui)) {
                        item { HeroCard(ui) }
                        item { ForceCard(ui) }
                        item { LogCard(ui) }
                    }
                    Tab.GESTURE -> TabScaffold("Gesture", "How your gesture is recognised") {
                        item { SensitivityCard(ui) }
                        item { DelayCard(ui) }
                        if (ui.engine == Engine.MODEL && ui.model != null) item { LearningCard(ui) }
                    }
                    Tab.VIBRATION -> TabScaffold("Vibration", "How the torch confirms it switched") {
                        item { VibrationCard(ui) }
                        if (ui.vibrate && ui.vibrationStrength == Strength.CUSTOM) item { CustomTimingCard(ui) }
                    }
                    Tab.SETTINGS -> TabScaffold("Settings", "Chop Torch ${ui.appVersion}") {
                        settingsItems(ui, openPage = { page = it })
                    }
                }
            }
        }
    }
}

private fun statusLine(ui: ChopUi) = when {
    !ui.running -> "Gesture is off"
    !ui.listening && ui.onlyLocked -> "Paused while unlocked"
    else -> "Listening for your gesture"
}

/** One tab: a large flexible top bar that collapses on scroll, over a list of cards. */
@Composable
internal fun TabScaffold(title: String, subtitle: String?, content: LazyListScope.() -> Unit) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title) },
                subtitle = subtitle?.let { { Text(it) } },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            )
        },
    ) { inner ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = inner.calculateTopPadding() + 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** A titled segmented group: rows sit 2 dp apart so they read as one rounded block. */
internal fun LazyListScope.group(title: String, rows: @Composable () -> Unit) {
    item {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 6.dp),
            )
            rows()
        }
    }
}

@Composable
internal fun rowColors() = ListItemDefaults.segmentedColors(
    containerColor = MaterialTheme.colorScheme.surfaceBright,
    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
    selectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    selectedSupportingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    selectedLeadingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    selectedTrailingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
)

@Composable
private fun HeroCard(ui: ChopUi) {
    // Cookie (torch off) morphs into a sun (torch on). The shape spins slowly while listening.
    val morph = remember { Morph(MaterialShapes.Cookie9Sided, MaterialShapes.Sunny) }
    val progress by animateFloatAsState(
        targetValue = if (ui.torchOn) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(),
        label = "morph",
    )
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Restart),
        label = "angle",
    )
    val blobColor by animateColorAsState(
        when {
            ui.torchOn -> MaterialTheme.colorScheme.tertiary
            ui.running -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outline
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "blob",
    )
    val iconColor = when {
        ui.torchOn -> MaterialTheme.colorScheme.onTertiary
        ui.running -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(24.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(168.dp)) {
                Box(
                    Modifier
                        .size(168.dp)
                        .graphicsLayer { rotationZ = if (ui.running) angle else 0f }
                        .clip(MorphShape(morph, progress))
                        .background(blobColor),
                )
                AnimatedContent(targetState = ui.torchOn, label = "icon") { on ->
                    Icon(
                        if (on) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff,
                        contentDescription = if (on) "Torch on" else "Torch off",
                        tint = iconColor,
                        modifier = Modifier.size(64.dp),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                when {
                    !ui.running -> "Gesture is off"
                    ui.onlyLocked -> "Works when locked"
                    ui.engine == Engine.MODEL -> "Do your gesture to toggle"
                    else -> "Chop twice to toggle"
                },
                style = MaterialTheme.typography.headlineSmallEmphasized,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                when {
                    ui.onlyLocked -> "Lock the phone or let the screen turn off, then do your gesture. It's paused while you use the phone."
                    ui.engine == Engine.MODEL -> "Your personal model is listening for the gesture you taught it."
                    else -> "Hold the phone firmly and chop down twice, like a Motorola."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
            )
            ToggleButton(
                checked = ui.enabled,
                onCheckedChange = ui::toggleGesture,
                buttonSize = ToggleButtonSize.Medium,
                shapes = ToggleButtonDefaults.shapesFor(ToggleButtonSize.Medium),
                icon = { Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null) },
            ) {
                Text(if (ui.enabled) "Turn gesture off" else "Turn gesture on")
            }
        }
    }
}

@Composable
private fun ForceCard(ui: ChopUi) {
    val trigger = if (ui.engine == Engine.AUTO) ui.auto.threshold else ui.threshold
    val fraction = (ui.peak / (trigger * 2f)).coerceIn(0f, 1f)
    val hot = ui.engine != Engine.MODEL && ui.peak >= trigger
    val color by animateColorAsState(
        if (hot) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
        label = "force",
    )
    SectionCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("Live force", style = MaterialTheme.typography.titleMediumEmphasized)
                Text(
                    when (ui.engine) {
                        Engine.MODEL -> "Your personal model decides by shape, not force"
                        Engine.AUTO -> "Trigger at %.1f m/s² (learned)".format(ui.auto.threshold)
                        Engine.CLASSIC -> "Trigger at %.1f m/s²".format(ui.threshold)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "%.1f".format(ui.peak),
                style = MaterialTheme.typography.displaySmallEmphasized,
                color = color,
            )
        }
        Spacer(Modifier.height(12.dp))
        LinearWavyProgressIndicator(
            progress = { fraction },
            color = color,
            modifier = Modifier.fillMaxWidth().height(14.dp),
        )
        if (!ui.running || !ui.listening) {
            Text(
                if (!ui.running) "Turn the gesture on to see the sensor."
                else "Paused while the phone is unlocked, so this reads 0. Calibration has its own live readout.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun SensitivityCard(ui: ChopUi) {
    // Slider value is the trigger force itself: left = light chop (sensitive), right = firm chop.
    val state = remember { SliderState(value = ui.threshold.coerceIn(T_MIN, T_MAX), trackRange = T_MIN..T_MAX) }
    LaunchedEffect(ui.threshold) {
        val target = ui.threshold.coerceIn(T_MIN, T_MAX)
        if (!state.isDragging && abs(state.value - target) > 0.01f) state.value = target
    }
    SectionCard {
        Text("Sensitivity", style = MaterialTheme.typography.titleMediumEmphasized)
        Text(
            "Auto learns by itself. You can also set the force by hand, or teach your gesture now instead of waiting.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EngineSelector(ui)
        AnimatedContent(targetState = ui.engine, label = "engine") { engine ->
            when (engine) {
                Engine.MODEL -> ModelControls(ui)
                Engine.AUTO -> AutoControls(ui)
                Engine.CLASSIC -> Column { ManualPresets(ui); ClassicControls(ui, state) }
            }
        }
        CalibrateButton(ui)
    }
}

@Composable
private fun ClassicControls(ui: ChopUi, state: SliderState) {
    run {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            FilledTonalIconButton(
                onClick = { ui.changeThreshold(stepThreshold(ui.threshold, -1)) },
                shapes = IconButtonDefaults.shapes(),
                enabled = ui.threshold > T_MIN,
                modifier = Modifier.size(52.dp),
            ) { Icon(Icons.Rounded.Remove, contentDescription = "Lighter: lower trigger force by 0.5") }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Text(
                    "%.1f".format(ui.threshold),
                    style = MaterialTheme.typography.displaySmallEmphasized,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "m/s² trigger force",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalIconButton(
                onClick = { ui.changeThreshold(stepThreshold(ui.threshold, +1)) },
                shapes = IconButtonDefaults.shapes(),
                enabled = ui.threshold < T_MAX,
                modifier = Modifier.size(52.dp),
            ) { Icon(Icons.Rounded.Add, contentDescription = "Firmer: raise trigger force by 0.5") }
        }
        Slider(
            state = state,
            onValueChange = { v ->
                state.value = v // required in Material3 1.5: the caller moves the thumb
                ui.changeThreshold((v * 10f).roundToInt() / 10f)
            },
            modifier = Modifier.padding(top = 4.dp),
        )
        Row {
            Text("Light chop", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text("Firm chop", style = MaterialTheme.typography.labelMedium)
        }
        val isDefault = ui.params == DetectorParams()
        val isMine = ui.myPreset != null && ui.params == ui.myPreset
        AnimatedVisibility(!isDefault && !isMine) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Icon(
                    Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    "Adjusted by hand",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = 6.dp),
                )
                TextButton(onClick = ui::saveCurrentAsPreset) {
                    Text(if (ui.myPreset == null) "Save as my preset" else "Update my preset")
                }
            }
        }
    }
}

@Composable
private fun CalibrateButton(ui: ChopUi) {
    run {
        Button(
            onClick = ui::openCalibration,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(ButtonDefaults.MediumContainerHeight),
            contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
        ) {
            Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(ButtonDefaults.MediumContainerHeight)))
            Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(ButtonDefaults.MediumContainerHeight)))
            Text(if (ui.model == null) "Teach my gesture now" else "Teach it again", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
        }
    }
}

/** Auto mode progress, as the screens see it. */
data class AutoInfo(
    val threshold: Float = 14f,
    /** Confirmed gesture forces, oldest first. */
    val forces: List<Float> = emptyList(),
    val gestures: Int = 0,
    val everyday: Int = 0,
    val modelAt: Long = 0L,
) {
    val usual: Float? get() = forces.takeIf { it.size >= 3 }?.takeLast(20)?.sorted()?.let { it[it.size / 2] }
}

/** Auto / Manual / Model as one connected button group. */
@Composable
private fun EngineSelector(ui: ChopUi) {
    val items = listOf(Engine.AUTO to "Auto", Engine.CLASSIC to "Manual", Engine.MODEL to "Model")
    Row(
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        items.forEachIndexed { i, (e, label) ->
            ToggleButton(
                checked = ui.engine == e,
                onCheckedChange = {
                    when (e) {
                        Engine.AUTO -> ui.useAuto()
                        Engine.CLASSIC -> ui.useManual()
                        Engine.MODEL -> if (ui.model != null) ui.useModel() else ui.openCalibration()
                    }
                },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    items.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
            ) {
                if (e == Engine.AUTO) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                } else if (e == Engine.MODEL) {
                    Icon(Icons.Rounded.Psychology, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(label, maxLines = 1)
            }
        }
    }
    Text(
        when (ui.engine) {
            Engine.AUTO -> "Learns the force you usually use and builds your personal model by itself."
            Engine.CLASSIC -> "You set the force. Default is 14 m/s²."
            Engine.MODEL -> "Your personal model: it recognises the shape of your own gesture."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, start = 4.dp),
    )
}

/** Manual mode: Default and My preset as quick picks above the force controls. */
@Composable
private fun ManualPresets(ui: ChopUi) {
    val default = remember { DetectorParams() }
    val mine = ui.myPreset
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
        FilterChip(
            selected = ui.params == default,
            onClick = { ui.applyPreset(default) },
            label = { Text("Default 14.0") },
        )
        FilterChip(
            selected = mine != null && ui.params == mine,
            onClick = { if (mine != null) ui.applyPreset(mine) else ui.openCalibration() },
            label = { Text(if (mine != null) "My preset %.1f".format(mine.threshold) else "My preset +") },
        )
    }
}

/** Auto mode: what it has learned about your force, and progress toward the personal model. */
@Composable
private fun AutoControls(ui: ChopUi) {
    val a = ui.auto
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("Trigger force now", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("%.1f".format(a.threshold), style = MaterialTheme.typography.displaySmallEmphasized, color = MaterialTheme.colorScheme.primary)
                    Text(" m/s²", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Your usual force", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    a.usual?.let { "%.1f".format(it) } ?: "learning…",
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        ForceHistogram(a)
        Text(
            when {
                a.forces.isEmpty() -> "Just use it. Every double chop you keep, or retry after a soft one, teaches it your force."
                a.forces.size < 3 -> "${a.forces.size} of your gestures measured. It starts adjusting after 3."
                else -> "Set to 70 % of your usual force, from your last ${minOf(20, a.forces.size)} gestures."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Psychology, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(
                "Personal model: ${minOf(a.gestures, 8)} of 8 gestures collected",
                style = MaterialTheme.typography.titleSmallEmphasized,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        LinearWavyProgressIndicator(
            progress = { (a.gestures / 8f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(12.dp).padding(top = 6.dp),
        )
        Text(
            "When it has 8, it trains the model on the phone and switches to it by itself" +
                (if (a.everyday > 0) ". Also kept ${a.everyday} short clips of everyday movement so it learns what to ignore." else "."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (a.forces.isNotEmpty() || a.gestures > 0) {
            TextButton(onClick = { confirmReset = true }) { Text("Start learning again") }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Start learning again?") },
            text = { Text("Forgets the forces and gestures Auto has collected and goes back to 14 m/s².") },
            confirmButton = { TextButton(onClick = { confirmReset = false; ui.resetAuto() }) { Text("Start again") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Keep") } },
        )
    }
}

/** Your confirmed forces in 2 m/s² buckets; the most common bucket and the trigger are highlighted. */
@Composable
private fun ForceHistogram(a: AutoInfo) {
    val buckets = 15 // 0..30 m/s²
    val counts = IntArray(buckets)
    a.forces.takeLast(20).forEach { counts[(it / 2f).toInt().coerceIn(0, buckets - 1)]++ }
    val max = counts.maxOrNull()?.coerceAtLeast(1) ?: 1
    val mode = counts.indices.maxByOrNull { counts[it] } ?: -1
    val trig = (a.threshold / 2f).toInt().coerceIn(0, buckets - 1)
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(top = 12.dp),
    ) {
        for (i in 0 until buckets) {
            val h = if (counts[i] == 0) 4.dp else (6 + 42 * counts[i] / max).dp
            val color = when {
                counts[i] > 0 && i == mode -> MaterialTheme.colorScheme.tertiary
                counts[i] > 0 -> MaterialTheme.colorScheme.primary
                i == trig -> MaterialTheme.colorScheme.outline
                else -> MaterialTheme.colorScheme.surfaceContainerHighest
            }
            Box(Modifier.weight(1f).height(h).clip(MaterialTheme.shapes.extraSmall).background(color))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Text("0", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        Text("15", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text("30 m/s²", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

/** Shown instead of the force controls while the personal model is in use. */
@Composable
private fun ModelControls(ui: ChopUi) {
    val m = ui.model
    Column(Modifier.padding(top = 12.dp)) {
        if (m == null) {
            Text("Loading your model…", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Text("Strictness", style = MaterialTheme.typography.titleSmallEmphasized)
        Row(
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Strictness.entries.forEachIndexed { i, s ->
                ToggleButton(
                    checked = m.strictness == s,
                    onCheckedChange = { ui.changeStrictness(s) },
                    shapes = when (i) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        Strictness.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                ) { Text(s.label, maxLines = 1) }
            }
        }
        Text(
            m.strictness.hint + " · knows ${m.positives} gesture samples and ${m.negatives} movements to ignore",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp),
        )
        OutlinedButton(
            onClick = ui::openTest,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(ButtonDefaults.MediumContainerHeight),
        ) {
            Icon(Icons.Rounded.Science, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Test & teach", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
        }
    }
}

/** Learning from everyday use: on/off, what it learned recently, undo, and forget. */
@Composable
private fun LearningCard(ui: ChopUi) {
    val m = ui.model ?: return
    var confirmForget by rememberSaveable { mutableStateOf(false) }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Learn from use", style = MaterialTheme.typography.titleMediumEmphasized)
                Text(
                    "Keeps improving on the phone from what you do next: repeating the gesture after a near miss, " +
                        "switching the torch yourself, or tapping Wasn't me after a wrong trigger.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = ui.learnEnabled, onCheckedChange = ui::changeLearn, modifier = Modifier.padding(start = 12.dp))
        }
        AnimatedVisibility(ui.strugglesToday >= 3) {
            Column(
                Modifier
                    .padding(top = 14.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                    .padding(14.dp),
            ) {
                Text(
                    "It missed you ${ui.strugglesToday} times today",
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    "If you now do the gesture differently, show it a few times so it catches up faster. " +
                        "It learns new styles slowly by itself, to avoid learning the wrong movements.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    FilledTonalButton(onClick = ui::openTest, shapes = ButtonDefaults.shapes()) { Text("Test & teach") }
                    TextButton(onClick = ui::openCalibration) { Text("Teach it again") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 14.dp)) {
            LearnStat("${m.learnedPositives}", "gesture variants", Modifier.weight(1f))
            LearnStat("${m.learnedNegatives}", "movements to ignore", Modifier.weight(1f))
        }
        val recent = ui.learnEvents.take(5)
        if (recent.isEmpty()) {
            Text(
                if (ui.learnEnabled) "Nothing learned yet. Just use it; lessons show up here."
                else "Learning is off. The model stays as you taught it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            Text(
                "Recently learned",
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                recent.forEachIndexed { i, e -> LearnRow(ui, e, i, recent.size) }
            }
        }
        if (m.learnedPositives + m.learnedNegatives > 0) {
            TextButton(onClick = { confirmForget = true }, modifier = Modifier.padding(top = 6.dp)) {
                Text("Forget everything learned since calibration")
            }
        }
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget learned samples?") },
            text = {
                Text("The model goes back to what you taught it during calibration. ${m.learnedPositives + m.learnedNegatives} learned samples are removed.")
            },
            confirmButton = {
                TextButton(onClick = { confirmForget = false; ui.forgetLearned() }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun LearnStat(value: String, label: String, modifier: Modifier) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(value, style = MaterialTheme.typography.headlineMediumEmphasized, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LearnRow(ui: ChopUi, e: LearnEvent, index: Int, count: Int) {
    val now = System.currentTimeMillis()
    val ago = android.text.format.DateUtils.getRelativeTimeSpanString(
        e.timeMs, now, android.text.format.DateUtils.MINUTE_IN_MILLIS,
    ).toString()
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        leadingContent = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(40.dp).clip(MaterialShapes.Cookie4Sided.toShape())
                    .background(if (e.positive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary),
            ) {
                Icon(
                    if (e.positive) Icons.Rounded.AddTask else Icons.Rounded.Block,
                    contentDescription = null,
                    tint = if (e.positive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onTertiary,
                    modifier = Modifier.size(22.dp),
                )
            }
        },
        supportingContent = { Text("${e.origin.reason} · $ago") },
        trailingContent = {
            if (e.undone) {
                Text("Undone", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                IconButton(onClick = { ui.undoLearning(e.sampleId) }) {
                    Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = "Undo", tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
    ) {
        Text(
            if (e.positive) "New gesture variant" else "New movement to ignore",
            style = if (e.undone) MaterialTheme.typography.bodyLarge.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
            else MaterialTheme.typography.bodyLarge,
        )
    }
}

private const val D_MIN = ChopDetector.MIN_COOLDOWN_MS / 1000f
private const val D_MAX = ChopDetector.MAX_COOLDOWN_MS / 1000f

/** Delay between toggles: 0.3–2.0 s in 0.1 s steps. */
@Composable
private fun DelayCard(ui: ChopUi) {
    val state = remember {
        SliderState(value = ui.cooldownSec.coerceIn(D_MIN, D_MAX), steps = 16, trackRange = D_MIN..D_MAX)
    }
    LaunchedEffect(ui.cooldownSec) {
        val target = ui.cooldownSec.coerceIn(D_MIN, D_MAX)
        if (!state.isDragging && abs(state.value - target) > 0.01f) state.value = target
    }
    SectionCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("Delay between toggles", style = MaterialTheme.typography.titleMediumEmphasized)
                Text(
                    "How long gestures are ignored after one switches the torch",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "%.1f s".format(ui.cooldownSec),
                style = MaterialTheme.typography.headlineMediumEmphasized,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        Slider(
            state = state,
            onValueChange = { v ->
                state.value = v // required in Material3 1.5: the caller moves the thumb
                ui.changeCooldown((v * 10f).roundToInt() / 10f)
            },
            modifier = Modifier.padding(top = 8.dp),
        )
        Row {
            Text("Faster", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text("Safer", style = MaterialTheme.typography.labelMedium)
        }
        Text(
            "Shorter lets you switch the torch back sooner. Very short can let one hard gesture, " +
                "and the bounce after it, switch the torch on and straight back off. Default 1.2 s." +
                if (ui.engine == Engine.MODEL) " Model mode also waits for your whole gesture to finish, so it adds about a second." else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Vibration on toggle: on/off, strength, and a button to feel it. */
@Composable
private fun VibrationCard(ui: ChopUi) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Vibration", style = MaterialTheme.typography.titleMediumEmphasized)
                Text(
                    "One buzz when the torch turns on, two when it turns off",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = ui.vibrate, onCheckedChange = ui::changeVibrate, modifier = Modifier.padding(start = 12.dp))
        }
        AnimatedVisibility(ui.vibrate) {
            Column {
                val levels = Strength.entries
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                ) {
                    levels.forEachIndexed { i, s ->
                        ToggleButton(
                            checked = ui.vibrationStrength == s,
                            onCheckedChange = { ui.changeVibrationStrength(s) },
                            shapes = when (i) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                levels.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                            // Four options share the width, so keep the side padding small.
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                        ) { Text(s.label, maxLines = 1, softWrap = false) }
                    }
                }
                PatternTimeline(ui.vibrationPattern, Modifier.padding(top = 14.dp))
                OutlinedButton(
                    onClick = ui::testVibration,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(ButtonDefaults.MediumContainerHeight),
                ) {
                    Icon(Icons.Rounded.Vibration, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Feel it: on, then off", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
                }
                Text(
                    "Still too weak? Raise the touch feedback strength in Settings → Sound & vibration → Vibration & haptics. " +
                        "This buzz follows that setting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** Custom vibration timing: one slider per length, plus intensity if the motor supports it. */
@Composable
private fun CustomTimingCard(ui: ChopUi) {
    val p = ui.customPattern
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Custom timing", style = MaterialTheme.typography.titleMediumEmphasized)
                Text(
                    "Let go of a slider to feel the result",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { ui.changeCustomPattern(Strength.STRONG.preset!!, Buzz.ON) }) { Text("Reset") }
        }
        MsSlider(
            label = "Torch on: one buzz",
            value = p.onMs,
            range = VibrationPattern.ON_RANGE,
            onChange = { ui.changeCustomPattern(ui.customPattern.copy(onMs = it), null) },
            onDone = { ui.changeCustomPattern(ui.customPattern, Buzz.ON) },
        )
        MsSlider(
            label = "Torch off: each of two buzzes",
            value = p.pulseMs,
            range = VibrationPattern.PULSE_RANGE,
            onChange = { ui.changeCustomPattern(ui.customPattern.copy(pulseMs = it), null) },
            onDone = { ui.changeCustomPattern(ui.customPattern, Buzz.OFF) },
        )
        MsSlider(
            label = "Torch off: pause between them",
            value = p.gapMs,
            range = VibrationPattern.GAP_RANGE,
            onChange = { ui.changeCustomPattern(ui.customPattern.copy(gapMs = it), null) },
            onDone = { ui.changeCustomPattern(ui.customPattern, Buzz.OFF) },
        )
        if (ui.hasAmplitudeControl) {
            IntensitySlider(
                value = p.amplitude,
                onChange = { ui.changeCustomPattern(ui.customPattern.copy(amplitude = it), null) },
                onDone = { ui.changeCustomPattern(ui.customPattern, Buzz.ON) },
            )
        } else {
            Text(
                "Your phone's vibration motor is on/off only, so it can't change intensity. Longer buzzes feel stronger.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** A slider in milliseconds, in 10 ms steps. */
@Composable
private fun MsSlider(label: String, value: Long, range: LongRange, onChange: (Long) -> Unit, onDone: () -> Unit) {
    val step = VibrationPattern.STEP_MS
    val steps = ((range.last - range.first) / step - 1).toInt()
    val state = remember { SliderState(value = value.toFloat(), steps = steps, trackRange = range.first.toFloat()..range.last.toFloat()) }
    LaunchedEffect(value) { if (!state.isDragging && abs(state.value - value) > 0.5f) state.value = value.toFloat() }
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 14.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Text("$value ms", style = MaterialTheme.typography.titleMediumEmphasized, color = MaterialTheme.colorScheme.primary)
    }
    Slider(
        state = state,
        onValueChange = { v ->
            state.value = v // required in Material3 1.5: the caller moves the thumb
            val ms = ((v / step).roundToInt() * step).coerceIn(range.first, range.last)
            if (ms != value) onChange(ms)
        },
        onValueChangeFinished = onDone,
    )
}

/** Intensity 10–255, shown as a percentage. */
@Composable
private fun IntensitySlider(value: Int, onChange: (Int) -> Unit, onDone: () -> Unit) {
    val r = VibrationPattern.AMP_RANGE
    val state = remember { SliderState(value = value.toFloat(), trackRange = r.first.toFloat()..r.last.toFloat()) }
    LaunchedEffect(value) { if (!state.isDragging && abs(state.value - value) > 0.5f) state.value = value.toFloat() }
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 14.dp)) {
        Text("Intensity", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Text("${(value * 100 / 255f).roundToInt()} %", style = MaterialTheme.typography.titleMediumEmphasized, color = MaterialTheme.colorScheme.primary)
    }
    Slider(
        state = state,
        onValueChange = { v ->
            state.value = v
            val a = v.roundToInt().coerceIn(r.first, r.last)
            if (a != value) onChange(a)
        },
        onValueChangeFinished = onDone,
    )
}

/** Draws the on and off patterns to scale (1.2 s across), with their lengths. */
@Composable
private fun PatternTimeline(p: VibrationPattern, modifier: Modifier = Modifier) {
    val on = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val scaleMs = 1200f
    @Composable
    fun Line(label: String, segments: List<Pair<Long, Boolean>>, detail: String) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(34.dp))
            Canvas(Modifier.weight(1f).height(14.dp)) {
                drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
                var x = 0f
                for ((ms, buzzing) in segments) {
                    val w = size.width * ms / scaleMs
                    if (buzzing) drawRoundRect(on, topLeft = Offset(x, 0f), size = Size(w, size.height), cornerRadius = CornerRadius(size.height / 2))
                    x += w
                }
            }
            Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.padding(start = 8.dp).width(96.dp))
        }
    }
    Column(modifier) {
        Line("On", listOf(p.onMs to true), "${p.onMs} ms")
        Line("Off", listOf(p.pulseMs to true, p.gapMs to false, p.pulseMs to true), "${p.pulseMs} + ${p.pulseMs} ms")
    }
}

@Composable
internal fun BehaviourGroup(ui: ChopUi) {
    val rows = listOf(
        Triple("Only when locked", "Works on the lock screen and with the screen off; paused while you use the phone", Icons.Rounded.Lock),
        Triple("Work with screen off", "Keeps one CPU core awake so the sensor keeps reporting", Icons.Rounded.NightsStay),
        Triple("Pocket guard", "Ignore chops while the proximity sensor is covered", Icons.Rounded.VisibilityOff),
    )
    rows.forEachIndexed { i, (title, sub, icon) ->
        val checked = when (i) { 0 -> ui.onlyLocked; 1 -> ui.screenOff; else -> ui.pocketGuard }
        val set: (Boolean) -> Unit = when (i) {
            0 -> ui::changeOnlyLocked; 1 -> ui::changeScreenOff; else -> ui::changePocketGuard
        }
        SegmentedListItem(
            checked = checked,
            onCheckedChange = set,
            shapes = ListItemDefaults.segmentedShapes(index = i, count = rows.size),
            colors = rowColors(),
            leadingContent = { LeadingIcon(icon) },
            supportingContent = { Text(sub) },
            trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        ) { Text(title) }
    }
}

@Composable
internal fun KeepAliveGroup(ui: ChopUi) {
    SegmentedListItem(
        onClick = ui::openBatterySettings,
        shapes = ListItemDefaults.segmentedShapes(index = 0, count = 3),
        colors = rowColors(),
        leadingContent = {
            LeadingIcon(if (ui.batteryExempt) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatterySaver)
        },
        supportingContent = {
            Text(if (ui.batteryExempt) "Exempt from battery optimisation" else "Not exempt. Tap to allow.")
        },
    ) { Text("Battery optimisation") }
    SegmentedListItem(
        onClick = { if (!ui.rootBusy) ui.applyRoot() },
        shapes = ListItemDefaults.segmentedShapes(index = 1, count = 3),
        colors = rowColors(),
        leadingContent = { LeadingIcon(Icons.Rounded.Security) },
        supportingContent = { Text("Doze whitelist and active standby bucket via su. Optional.") },
        trailingContent = {
            AnimatedVisibility(ui.rootBusy, enter = fadeIn(), exit = fadeOut()) {
                LoadingIndicator(Modifier.size(36.dp))
            }
        },
    ) { Text("Root keep-alive") }
    SegmentedListItem(
        onClick = ui::testTorch,
        shapes = ListItemDefaults.segmentedShapes(index = 2, count = 3),
        colors = rowColors(),
        leadingContent = { LeadingIcon(Icons.Rounded.Highlight) },
        supportingContent = { Text("Toggle the flashlight without the gesture") },
    ) { Text("Test torch") }
}

@Composable
private fun LogCard(ui: ChopUi) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Gesture log", style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = ui::clearLog, shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Clear")
            }
        }
        Spacer(Modifier.height(8.dp))
        AnimatedContent(targetState = ui.log.isEmpty(), label = "log") { empty ->
            if (empty) {
                Text(
                    "Chop the phone and events show up here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(12.dp),
                ) {
                    ui.log.take(25).forEach { line ->
                        Text(
                            line,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (line.contains("TORCH")) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SectionCard(content: @Composable () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) { content() }
    }
}

@Composable
internal fun LeadingIcon(icon: ImageVector) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(MaterialShapes.Cookie4Sided.toShape())
            .background(MaterialTheme.colorScheme.primary),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
    }
}
