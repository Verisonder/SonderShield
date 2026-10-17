package com.verisonder.sondershield.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One theme's colours. Every screen reads them through [Palette]. */
data class ShieldColors(
    val bg: Color, val card: Color, val cardHi: Color, val line: Color,
    val text: Color, val dim: Color, val accent: Color, val accentSoft: Color, val onAccent: Color,
    val blocked: Color, val warn: Color, val off: Color, val dark: Boolean,
)

enum class ThemeMode(val id: String, val label: String) {
    Default("default", "Default"), Light("light", "Light"), Oled("oled", "OLED"), You("you", "Material You");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Default
        /** Material You needs Android 12. */
        val youAvailable get() = Build.VERSION.SDK_INT >= 31
    }
}

/** The original look: deep slate, one teal accent, red only for "blocked". */
val SlateColors = ShieldColors(
    bg = Color(0xFF0E1116), card = Color(0xFF161B22), cardHi = Color(0xFF1C232C), line = Color(0xFF232A33),
    text = Color(0xFFE6EDF3), dim = Color(0xFF8B97A5), accent = Color(0xFF2EE6C5), accentSoft = Color(0x222EE6C5),
    onAccent = Color(0xFF00201A), blocked = Color(0xFFFF6B6B), warn = Color(0xFFFFC857), off = Color(0xFF4A5563), dark = true,
)

val OledColors = SlateColors.copy(
    bg = Color(0xFF000000), card = Color(0xFF0B0D10), cardHi = Color(0xFF13171C), line = Color(0xFF1B2027),
)

val LightColors = ShieldColors(
    bg = Color(0xFFF4F6F8), card = Color(0xFFFFFFFF), cardHi = Color(0xFFEDF1F4), line = Color(0xFFDDE3E9),
    text = Color(0xFF0E1116), dim = Color(0xFF5B6672), accent = Color(0xFF0B9E86), accentSoft = Color(0x1F0B9E86),
    onAccent = Color(0xFFFFFFFF), blocked = Color(0xFFD63B3B), warn = Color(0xFFB7791F), off = Color(0xFF9AA5B1), dark = false,
)

/** Material You: the wallpaper's colours, light or dark with the system. */
private fun youColors(s: ColorScheme, dark: Boolean) = ShieldColors(
    bg = s.surface, card = s.surfaceContainer, cardHi = s.surfaceContainerHigh, line = s.outlineVariant,
    text = s.onSurface, dim = s.onSurfaceVariant, accent = s.primary, accentSoft = s.primary.copy(alpha = 0.14f),
    onAccent = s.onPrimary, blocked = if (dark) Color(0xFFFF6B6B) else Color(0xFFD63B3B),
    warn = if (dark) Color(0xFFFFC857) else Color(0xFFB7791F), off = s.outline, dark = dark,
)

val LocalShieldColors = staticCompositionLocalOf { SlateColors }

/** Colours of the current theme. Read inside composables only. */
object Palette {
    private val c: ShieldColors @Composable @ReadOnlyComposable get() = LocalShieldColors.current
    val bg @Composable @ReadOnlyComposable get() = c.bg
    val card @Composable @ReadOnlyComposable get() = c.card
    val cardHi @Composable @ReadOnlyComposable get() = c.cardHi
    val line @Composable @ReadOnlyComposable get() = c.line
    val text @Composable @ReadOnlyComposable get() = c.text
    val dim @Composable @ReadOnlyComposable get() = c.dim
    val accent @Composable @ReadOnlyComposable get() = c.accent
    val accentSoft @Composable @ReadOnlyComposable get() = c.accentSoft
    val onAccent @Composable @ReadOnlyComposable get() = c.onAccent
    val blocked @Composable @ReadOnlyComposable get() = c.blocked
    val warn @Composable @ReadOnlyComposable get() = c.warn
    val off @Composable @ReadOnlyComposable get() = c.off
}

private fun schemeOf(c: ShieldColors): ColorScheme {
    val base = if (c.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft, onPrimaryContainer = c.accent,
        secondary = c.accent, onSecondary = c.onAccent,
        background = c.bg, onBackground = c.text, surface = c.bg, onSurface = c.text,
        surfaceVariant = c.card, onSurfaceVariant = c.dim,
        surfaceContainer = c.card, surfaceContainerHigh = c.cardHi, surfaceContainerHighest = c.cardHi,
        surfaceContainerLow = c.card, surfaceContainerLowest = c.bg,
        outline = c.line, outlineVariant = c.line, error = c.blocked,
    )
}

private val type = Typography().let { t ->
    t.copy(
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

val numberStyle: TextStyle @Composable get() = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.text)

/** Resolves a theme setting to colours (Material You falls back to Default before Android 12). */
@Composable
fun colorsFor(mode: ThemeMode): ShieldColors = when (mode) {
    ThemeMode.Default -> SlateColors
    ThemeMode.Light -> LightColors
    ThemeMode.Oled -> OledColors
    ThemeMode.You -> if (Build.VERSION.SDK_INT >= 31) {
        val ctx = LocalContext.current
        val dark = isSystemInDarkTheme()
        youColors(if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx), dark)
    } else SlateColors
}

@Composable
fun ShieldTheme(colors: ShieldColors, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalShieldColors provides colors) {
        MaterialTheme(colorScheme = schemeOf(colors), typography = type, content = content)
    }
}

/** Small line icons from SVG paths (24×24), so nothing beyond material3 is needed. */
object Icons {
    private fun icon(name: String, vararg paths: String, stroke: Boolean = true): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (p in paths) {
            if (stroke) {
                b.addPath(
                    addPathNodes(p), stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
                    strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
                    strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
                )
            } else {
                b.addPath(addPathNodes(p), fill = SolidColor(Color.White))
            }
        }
        return b.build()
    }

    /** The SonderShield mark: a hexagon crest with an S. */
    val logo = icon("logo", "M12 2.8l7.6 4.4v9.6L12 21.2l-7.6-4.4V7.2z", "M13.9 7.5h-2.9a2.26 2.26 0 0 0 0 4.52h1.94a2.26 2.26 0 0 1 0 4.52H10.1")
    val home = icon("home", "M3.5 10.5L12 4l8.5 6.5", "M5.5 9v10.5h13V9", "M10 19.5v-5h4v5")
    val log = icon("log", "M8 6h12", "M8 12h12", "M8 18h12", "M4 6h.01", "M4 12h.01", "M4 18h.01")
    val rules = icon("rules", "M4 7h10", "M4 17h6", "M18 4v6", "M15 7h6", "M14 17h6")
    val apps = icon("apps", "M4 4h6v6H4z", "M14 4h6v6h-6z", "M4 14h6v6H4z", "M14 14h6v6h-6z")
    val settings = icon(
        "settings",
        "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
        "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z",
    )
    val search = icon("search", "M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z", "M20 20l-4-4")
    val plus = icon("plus", "M12 5v14", "M5 12h14")
    val close = icon("close", "M6 6l12 12", "M18 6L6 18")
    val refresh = icon("refresh", "M20 11a8 8 0 1 0-2.3 5.7", "M20 4v7h-7")
    val trash = icon("trash", "M4 7h16", "M9 7V4h6v3", "M6 7l1 13h10l1-13")
    val info = icon("info", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z", "M12 11v5", "M12 8h.01")
    val chevron = icon("chevron", "M9 6l6 6-6 6")
}
