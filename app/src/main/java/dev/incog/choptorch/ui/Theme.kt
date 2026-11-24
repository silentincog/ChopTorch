package dev.incog.choptorch.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.dynamiccolor.DynamicScheme
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeMonochrome
import com.materialkolor.scheme.SchemeTonalSpot

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/**
 * Colour palettes. [WALLPAPER] uses the phone's wallpaper colours (Android 12+); the others are
 * full Material 3 schemes generated from one seed colour, the same way Android does it.
 */
enum class Palette(val label: String, val seed: Long) {
    WALLPAPER("Wallpaper", 0xFF3F51B5),
    INDIGO("Indigo", 0xFF3F51B5),
    OCEAN("Ocean", 0xFF0277BD),
    TEAL("Teal", 0xFF00897B),
    FOREST("Forest", 0xFF2E7D32),
    SUNSET("Sunset", 0xFFE64A19),
    ROSE("Rose", 0xFFC2185B),
    GRAPE("Grape", 0xFF7B1FA2),
    MONO("Mono", 0xFF757575),
    ;

    val seedColor: Color get() = Color(seed)
}

data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val palette: Palette = Palette.WALLPAPER,
    /** Pure black backgrounds in dark mode (saves power on OLED screens). */
    val amoled: Boolean = false,
)

/** True if wallpaper colours are available on this phone. */
val wallpaperColorsSupported: Boolean get() = Build.VERSION.SDK_INT >= 31

/** Material 3 Expressive theme with the chosen palette and mode, and the expressive motion scheme. */
@Composable
fun ChopTheme(settings: ThemeSettings = ThemeSettings(), content: @Composable () -> Unit) {
    val dark = when (settings.mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val ctx = LocalContext.current
    val base = if (settings.palette == Palette.WALLPAPER && wallpaperColorsSupported) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        val p = if (settings.palette == Palette.WALLPAPER) Palette.INDIGO else settings.palette
        remember(p, dark) { seededScheme(p, dark) }
    }
    val colors = if (dark && settings.amoled) remember(base) { base.amoled() } else base
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

/** A complete colour scheme from one seed colour, using Material Color Utilities. */
fun seededScheme(p: Palette, dark: Boolean): ColorScheme {
    val hct = Hct.fromInt(p.seed.toInt())
    val s: DynamicScheme = if (p == Palette.MONO) SchemeMonochrome(hct, dark, 0.0) else SchemeTonalSpot(hct, dark, 0.0)
    val m = MaterialDynamicColors()
    fun c(role: com.materialkolor.dynamiccolor.DynamicColor) = Color(role.getArgb(s))
    return ColorScheme(
        primary = c(m.primary), onPrimary = c(m.onPrimary),
        primaryContainer = c(m.primaryContainer), onPrimaryContainer = c(m.onPrimaryContainer),
        inversePrimary = c(m.inversePrimary),
        secondary = c(m.secondary), onSecondary = c(m.onSecondary),
        secondaryContainer = c(m.secondaryContainer), onSecondaryContainer = c(m.onSecondaryContainer),
        tertiary = c(m.tertiary), onTertiary = c(m.onTertiary),
        tertiaryContainer = c(m.tertiaryContainer), onTertiaryContainer = c(m.onTertiaryContainer),
        background = c(m.background), onBackground = c(m.onBackground),
        surface = c(m.surface), onSurface = c(m.onSurface),
        surfaceVariant = c(m.surfaceVariant), onSurfaceVariant = c(m.onSurfaceVariant),
        surfaceTint = c(m.surfaceTint),
        inverseSurface = c(m.inverseSurface), inverseOnSurface = c(m.inverseOnSurface),
        error = c(m.error), onError = c(m.onError),
        errorContainer = c(m.errorContainer), onErrorContainer = c(m.onErrorContainer),
        outline = c(m.outline), outlineVariant = c(m.outlineVariant), scrim = c(m.scrim),
        surfaceBright = c(m.surfaceBright), surfaceDim = c(m.surfaceDim),
        surfaceContainer = c(m.surfaceContainer),
        surfaceContainerHigh = c(m.surfaceContainerHigh), surfaceContainerHighest = c(m.surfaceContainerHighest),
        surfaceContainerLow = c(m.surfaceContainerLow), surfaceContainerLowest = c(m.surfaceContainerLowest),
        primaryFixed = c(m.primaryFixed), primaryFixedDim = c(m.primaryFixedDim),
        onPrimaryFixed = c(m.onPrimaryFixed), onPrimaryFixedVariant = c(m.onPrimaryFixedVariant),
        secondaryFixed = c(m.secondaryFixed), secondaryFixedDim = c(m.secondaryFixedDim),
        onSecondaryFixed = c(m.onSecondaryFixed), onSecondaryFixedVariant = c(m.onSecondaryFixedVariant),
        tertiaryFixed = c(m.tertiaryFixed), tertiaryFixedDim = c(m.tertiaryFixedDim),
        onTertiaryFixed = c(m.onTertiaryFixed), onTertiaryFixedVariant = c(m.onTertiaryFixedVariant),
    )
}

/** Dark scheme with a pure black background; raised surfaces stay slightly lighter so cards still read. */
private fun ColorScheme.amoled(): ColorScheme {
    fun toward(c: Color, keep: Float) = lerp(Color.Black, c, keep)
    return copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = toward(surfaceContainerLow, 0.45f),
        surfaceContainer = toward(surfaceContainer, 0.55f),
        surfaceContainerHigh = toward(surfaceContainerHigh, 0.65f),
        surfaceContainerHighest = toward(surfaceContainerHighest, 0.75f),
        surfaceBright = toward(surfaceBright, 0.7f),
    )
}
