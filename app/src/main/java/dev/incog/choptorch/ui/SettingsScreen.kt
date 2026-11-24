package dev.incog.choptorch.ui

import androidx.compose.material3.TextButton
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.incog.choptorch.Legal
import dev.incog.choptorch.R

/** Full-screen pages opened from Settings. */
enum class SettingsPage(val title: String) {
    PRIVACY("Privacy policy"),
    LICENSE("Licence"),
    THIRD_PARTY("Open-source licences"),
    /** Opened from Home with "View logs" (Settings → Advanced → Show gesture log). */
    LOGS("Gesture log"),
}

/** The Settings tab. */
internal fun LazyListScope.settingsItems(ui: ChopUi, openPage: (SettingsPage) -> Unit) {
    if (ui.oldAppInstalled || ui.importMessage != null) item { ImportCard(ui) }
    item { AppearanceCard(ui) }
    group("Behaviour") { BehaviourGroup(ui) }
    group("Keep alive") { KeepAliveGroup(ui) }
    group("Advanced") { AdvancedGroup(ui) }
    item { AboutCard(ui) }
    group("Legal") { LegalGroup(openPage) }
}

/** Advanced: things most people never need. */
@Composable
private fun AdvancedGroup(ui: ChopUi) {
    SegmentedListItem(
        checked = ui.showLogs,
        onCheckedChange = ui::changeShowLogs,
        shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
        colors = rowColors(),
        leadingContent = { LeadingIcon(Icons.AutoMirrored.Rounded.ReceiptLong) },
        supportingContent = { Text("Shows View logs on Home: every chop, match and decision, for troubleshooting") },
        trailingContent = { Switch(checked = ui.showLogs, onCheckedChange = null) },
    ) { Text("Show gesture log") }
}

/** Theme: light/dark/system, palette swatches, and pure black. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceCard(ui: ChopUi) {
    val t = ui.theme
    val dark = when (t.mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    SectionCard {
        Text("Appearance", style = MaterialTheme.typography.titleMediumEmphasized)
        Text(
            "Theme for the whole app",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val modes = ThemeMode.entries
        Row(
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        ) {
            modes.forEachIndexed { i, m ->
                ToggleButton(
                    checked = t.mode == m,
                    onCheckedChange = { ui.changeTheme(t.copy(mode = m)) },
                    shapes = when (i) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        modes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                ) { Text(m.label, maxLines = 1) }
            }
        }
        Text("Colours", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))
        val palettes = Palette.entries.filter { it != Palette.WALLPAPER || wallpaperColorsSupported }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = 5,
            modifier = Modifier.fillMaxWidth(),
        ) {
            palettes.forEach { p -> Swatch(p, dark, selected = t.palette == p) { ui.changeTheme(t.copy(palette = p)) } }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .clip(MaterialTheme.shapes.large)
                .clickable(enabled = dark) { ui.changeTheme(t.copy(amoled = !t.amoled)) }
                .padding(vertical = 6.dp),
        ) {
            Icon(Icons.Rounded.DarkMode, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Pure black", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (dark) "Black background in dark mode; saves power on OLED screens" else "Available in dark mode",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = t.amoled, onCheckedChange = { ui.changeTheme(t.copy(amoled = it)) }, enabled = dark)
        }
    }
}

/** A palette preview: primary, secondary and tertiary of that palette in the current mode. */
@Composable
private fun Swatch(p: Palette, dark: Boolean, selected: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = remember(p, dark) {
        if (p == Palette.WALLPAPER && Build.VERSION.SDK_INT >= 31) {
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        } else {
            seededScheme(p, dark)
        }
    }
    val ring by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, label = "ring")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(56.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .semantics { role = Role.RadioButton },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(52.dp)
                .border(BorderStroke(3.dp, ring), CircleShape)
                .padding(5.dp)
                .clip(CircleShape),
        ) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().weight(1f).background(scheme.primary))
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    Box(Modifier.weight(1f).fillMaxSize().background(scheme.secondaryContainer))
                    Box(Modifier.weight(1f).fillMaxSize().background(scheme.tertiary))
                }
            }
            if (selected) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(24.dp).clip(CircleShape).background(scheme.primaryContainer),
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(16.dp))
                }
            }
        }
        Text(
            p.label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Shown while Chop Torch 3.x is still installed: import its data, then uninstall it. */
@Composable
private fun ImportCard(ui: ChopUi) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val on = MaterialTheme.colorScheme.onTertiaryContainer
        Column(Modifier.padding(20.dp)) {
            Text(
                if (ui.oldAppInstalled) "Chop Torch 3.x is still installed" else "Chop Torch 3.x",
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = on,
            )
            if (ui.oldAppInstalled) {
                Text(
                    "This version is a new app (dev.incog.choptorch), so it started with fresh settings. " +
                        "While both run, one gesture switches the torch twice. Import your settings and trained " +
                        "model (needs root), then uninstall the old app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = on,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            ui.importMessage?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = on, modifier = Modifier.padding(top = 8.dp))
            }
            if (ui.oldAppInstalled) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                ) {
                    Button(
                        onClick = ui::importFromOldApp,
                        enabled = !ui.importBusy,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.weight(1f),
                    ) { Text(if (ui.importBusy) "Importing…" else "Import (root)") }
                    OutlinedButton(
                        onClick = ui::uninstallOldApp,
                        shapes = ButtonDefaults.shapes(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = on),
                        border = BorderStroke(1.dp, on.copy(alpha = 0.6f)),
                        modifier = Modifier.weight(1f),
                    ) { Text("Uninstall 3.x") }
                    AnimatedVisibility(ui.importBusy) { LoadingIndicator(Modifier.size(36.dp)) }
                }
            }
        }
    }
}

/** App icon, version, "Made with love by incogcyberpunk", author and releases. */
@Composable
private fun AboutCard(ui: ChopUi) {
    SectionCard {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(MaterialShapes.Cookie9Sided.toShape())
                    .background(Brush.linearGradient(listOf(Color(0xFF3F51B5), Color(0xFF1A1F4D)))),
            ) {
                Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
            }
            Text("Chop Torch", style = MaterialTheme.typography.headlineSmallEmphasized, modifier = Modifier.padding(top = 12.dp))
            Text(
                "Version ${ui.appVersion}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MadeWithLove(Modifier.padding(top = 14.dp))
        }
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            InfoRow(Icons.Rounded.Person, "Author", Legal.AUTHOR, 0, 3)
            InfoRow(Icons.Rounded.Inventory2, "Package", "dev.incog.choptorch", 1, 3)
            SegmentedListItem(
                onClick = ui::openReleases,
                shapes = ListItemDefaults.segmentedShapes(index = 2, count = 3),
                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                leadingContent = { LeadingIcon(Icons.Rounded.SystemUpdate) },
                supportingContent = { Text("Download new versions from GitHub") },
                trailingContent = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
            ) { Text("Releases") }
        }
    }
}

/** "Made with ♥ by incogcyberpunk". */
@Composable
internal fun MadeWithLove(modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        val c = MaterialTheme.colorScheme.onSecondaryContainer
        Text("Made with ", style = MaterialTheme.typography.labelLarge, color = c)
        Icon(Icons.Rounded.Favorite, contentDescription = "love", tint = Color(0xFFE53935), modifier = Modifier.size(18.dp))
        Text(" by ", style = MaterialTheme.typography.labelLarge, color = c)
        Text(Legal.AUTHOR, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = c)
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, value: String, index: Int, count: Int) {
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        leadingContent = { LeadingIcon(icon) },
        supportingContent = { Text(value) },
    ) { Text(title) }
}

@Composable
private fun LegalGroup(openPage: (SettingsPage) -> Unit) {
    val rows = listOf(
        Triple(SettingsPage.PRIVACY, "No data collected; nothing leaves your phone", Icons.Rounded.PrivacyTip),
        Triple(SettingsPage.LICENSE, "MIT License", Icons.Rounded.Gavel),
        Triple(SettingsPage.THIRD_PARTY, "Libraries used in this app", Icons.Rounded.Code),
    )
    rows.forEachIndexed { i, (page, sub, icon) ->
        SegmentedListItem(
            onClick = { openPage(page) },
            shapes = ListItemDefaults.segmentedShapes(index = i, count = rows.size),
            colors = rowColors(),
            leadingContent = { LeadingIcon(icon) },
            supportingContent = { Text(sub) },
        ) { Text(page.title) }
    }
}

/** Privacy policy, licence and open-source licences. */
@Composable
internal fun SettingsPageScreen(page: SettingsPage, ui: ChopUi, onBack: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = { Text(page.title, style = MaterialTheme.typography.titleLargeEmphasized) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (page == SettingsPage.LOGS && ui.log.isNotEmpty()) {
                        TextButton(onClick = ui::clearLog) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Clear")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
    ) { inner ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = inner.calculateTopPadding() + 8.dp,
                bottom = inner.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (page) {
                SettingsPage.LOGS -> logItems(ui)
                SettingsPage.PRIVACY -> {
                    item {
                        Text(
                            "Effective ${Legal.PRIVACY_EFFECTIVE}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    Legal.PRIVACY.forEach { sec ->
                        item {
                            SectionCard {
                                Text(sec.title, style = MaterialTheme.typography.titleMediumEmphasized)
                                Text(sec.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
                SettingsPage.LICENSE -> {
                    item {
                        SectionCard {
                            Text("Chop Torch is open source under the MIT License.", style = MaterialTheme.typography.titleMediumEmphasized)
                            Text(
                                Legal.reflow(Legal.MIT_LICENSE),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                }
                SettingsPage.THIRD_PARTY -> {
                    Legal.THIRD_PARTY.forEach { c ->
                        item {
                            SectionCard {
                                Text(c.name, style = MaterialTheme.typography.titleMediumEmphasized)
                                Text(c.by, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(c.license, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                    item {
                        val ctx = LocalContext.current
                        val apache = remember { Legal.reflow(ctx.resources.openRawResource(R.raw.apache_2_0).bufferedReader().use { it.readText() }) }
                        SectionCard {
                            Text("Apache License 2.0", style = MaterialTheme.typography.titleMediumEmphasized)
                            Text(apache, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                    item {
                        SectionCard {
                            Text("MIT License (MaterialKolor)", style = MaterialTheme.typography.titleMediumEmphasized)
                            Text(
                                "Copyright (c) Jordon de Hoog. Licensed under the MIT License; the full text is the same " +
                                    "as Chop Torch's licence on the Licence page, with this copyright line.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
            item { Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) { MadeWithLove() } }
        }
    }
}

