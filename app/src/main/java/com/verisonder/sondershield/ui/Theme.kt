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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verisonder.sondershield.R

/**
 * One theme's colours. The look: near-black, one loud accent, white "pills" for picked
 * filters, panels a step lighter than the page. Every screen reads them through [Palette].
 */
data class ShieldColors(
    val bg: Color,
    /** The section under the swoosh, and cards on the page. */
    val panel: Color,
    /** Cards sitting on a panel. */
    val card2: Color,
    val line: Color,
    val text: Color,
    val dim: Color,
    val faint: Color,
    /** Fills: buttons, the last ladder step, the picked tab, toggles. */
    val accent: Color,
    /** The accent used as text on [bg]. Same as accent except on light themes. */
    val accentText: Color,
    val onAccent: Color,
    /** Picked filter chip. */
    val pill: Color,
    val onPill: Color,
    val warn: Color,
    /** Logo top piece and swoosh when protection is off. */
    val off: Color,
    val dark: Boolean,
)

enum class ThemeMode(val id: String, val label: String, val fr: String, val de: String) {
    Default("default", "Default", "Par défaut", "Standard"), Light("light", "Light", "Clair", "Hell"),
    System("system", "System", "Système", "System"),
    You("you", "Material You", "Material You", "Material You");

    companion object {
        /** Unknown ids, including the old "oled" (now the default), fall back to Default. */
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Default
        /** Material You needs Android 12. */
        val youAvailable get() = Build.VERSION.SDK_INT >= 31
    }
}

/** The accent. One value: change it here to recolour the app. */
val Electric = Color(0xFF3B82FF)

/** Default: pure black (what used to be the OLED theme), panels a step lighter. */
val DefaultColors = ShieldColors(
    bg = Color(0xFF000000), panel = Color(0xFF111111), card2 = Color(0xFF1E1E1E), line = Color(0xFF1A1A1A),
    text = Color(0xFFFFFFFF), dim = Color(0xFFBDBDBD), faint = Color(0xFF8A8A8A),
    accent = Electric, accentText = Electric, onAccent = Color(0xFF0D0D0D),
    pill = Color(0xFFFFFFFF), onPill = Color(0xFF0D0D0D), warn = Color(0xFFFFB020), off = Color(0xFF3A3A3A), dark = true,
)

/** System theme at night: the original soft dark (near-black grey instead of pure black). */
val SoftDarkColors = DefaultColors.copy(
    bg = Color(0xFF0D0D0D), panel = Color(0xFF1C1C1C), card2 = Color(0xFF2A2A2A), line = Color(0xFF262626),
)

val LightColors = ShieldColors(
    bg = Color(0xFFF5F5F4), panel = Color(0xFFFFFFFF), card2 = Color(0xFFF0F0EE), line = Color(0xFFE2E2E0),
    text = Color(0xFF0D0D0D), dim = Color(0xFF3A3A3A), faint = Color(0xFF6B6B6B),
    accent = Electric, accentText = Color(0xFF1D5BD8), onAccent = Color(0xFF0D0D0D),
    pill = Color(0xFF0D0D0D), onPill = Color(0xFFFFFFFF), warn = Color(0xFFB26A00), off = Color(0xFFC7C7C7), dark = false,
)

/** Material You: the wallpaper's colours, light or dark with the system. */
private fun youColors(s: ColorScheme, dark: Boolean) = ShieldColors(
    bg = s.surface, panel = s.surfaceContainer, card2 = s.surfaceContainerHighest, line = s.outlineVariant,
    text = s.onSurface, dim = s.onSurfaceVariant, faint = s.outline,
    accent = s.primary, accentText = s.primary, onAccent = s.onPrimary,
    pill = s.onSurface, onPill = s.surface, warn = if (dark) Color(0xFFFFB020) else Color(0xFFB26A00),
    off = s.outlineVariant, dark = dark,
)

val LocalShieldColors = staticCompositionLocalOf { DefaultColors }

/** Colours of the current theme. Read inside composables only. */
object Palette {
    private val c: ShieldColors @Composable @ReadOnlyComposable get() = LocalShieldColors.current
    val bg @Composable @ReadOnlyComposable get() = c.bg
    val panel @Composable @ReadOnlyComposable get() = c.panel
    val card2 @Composable @ReadOnlyComposable get() = c.card2
    val line @Composable @ReadOnlyComposable get() = c.line
    val text @Composable @ReadOnlyComposable get() = c.text
    val dim @Composable @ReadOnlyComposable get() = c.dim
    val faint @Composable @ReadOnlyComposable get() = c.faint
    val accent @Composable @ReadOnlyComposable get() = c.accent
    val accentText @Composable @ReadOnlyComposable get() = c.accentText
    val onAccent @Composable @ReadOnlyComposable get() = c.onAccent
    val pill @Composable @ReadOnlyComposable get() = c.pill
    val onPill @Composable @ReadOnlyComposable get() = c.onPill
    val warn @Composable @ReadOnlyComposable get() = c.warn
    val off @Composable @ReadOnlyComposable get() = c.off
    val dark @Composable @ReadOnlyComposable get() = c.dark
}

/** Unbounded: headings and numbers. */
val Display = FontFamily(
    Font(R.font.unbounded_bold, FontWeight.Bold),
    Font(R.font.unbounded_extrabold, FontWeight.ExtraBold),
)

/** DM Sans: everything else. */
val Body = FontFamily(
    Font(R.font.dmsans_regular, FontWeight.Normal),
    Font(R.font.dmsans_medium, FontWeight.Medium),
    Font(R.font.dmsans_bold, FontWeight.Bold),
)

/** Big Unbounded heading, slightly tightened like the reference. */
fun display(size: Int, weight: FontWeight = FontWeight.ExtraBold) = TextStyle(
    fontFamily = Display, fontWeight = weight, fontSize = size.sp, lineHeight = (size * 1.08f).sp,
    letterSpacing = (-0.025f * size).sp,
)

private fun schemeOf(c: ShieldColors): ColorScheme {
    val base = if (c.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accent, onPrimaryContainer = c.onAccent,
        secondary = c.accent, onSecondary = c.onAccent,
        background = c.bg, onBackground = c.text, surface = c.bg, onSurface = c.text,
        surfaceVariant = c.panel, onSurfaceVariant = c.dim,
        surfaceContainer = c.panel, surfaceContainerHigh = c.panel, surfaceContainerHighest = c.card2,
        surfaceContainerLow = c.panel, surfaceContainerLowest = c.bg,
        outline = c.line, outlineVariant = c.line,
    )
}

private val type = Typography().let { t ->
    fun TextStyle.body() = copy(fontFamily = Body)
    t.copy(
        displayLarge = t.displayLarge.copy(fontFamily = Display, fontWeight = FontWeight.ExtraBold),
        displayMedium = t.displayMedium.copy(fontFamily = Display, fontWeight = FontWeight.ExtraBold),
        displaySmall = t.displaySmall.copy(fontFamily = Display, fontWeight = FontWeight.ExtraBold),
        headlineLarge = t.headlineLarge.copy(fontFamily = Display, fontWeight = FontWeight.ExtraBold),
        headlineMedium = t.headlineMedium.copy(fontFamily = Display, fontWeight = FontWeight.ExtraBold),
        headlineSmall = t.headlineSmall.copy(fontFamily = Display, fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontFamily = Body, fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontFamily = Body, fontWeight = FontWeight.Bold),
        titleSmall = t.titleSmall.body(),
        bodyLarge = t.bodyLarge.body(), bodyMedium = t.bodyMedium.body(), bodySmall = t.bodySmall.body(),
        labelLarge = t.labelLarge.copy(fontFamily = Body, fontWeight = FontWeight.Bold),
        labelMedium = t.labelMedium.body(), labelSmall = t.labelSmall.body(),
    )
}

/** Accent choices in Settings. Any other colour comes from the custom hue slider. */
val AccentChoices = listOf(
    "Electric" to Color(0xFF3B82FF), "Sky" to Color(0xFF38B6FF), "Indigo" to Color(0xFF5B6CFF),
    "Cyan" to Color(0xFF00D1FF), "Lime" to Color(0xFFC8FF2E), "Green" to Color(0xFF00C46A),
    "Yellow" to Color(0xFFFFD60A), "Orange" to Color(0xFFFF7A1A), "Red" to Color(0xFFFF4D3D),
    "Pink" to Color(0xFFFF4FA3), "Purple" to Color(0xFFA66BFF), "White" to Color(0xFFF2F2F2),
)

/** Black or white, whichever has more contrast on [c] (WCAG ratio). */
fun onColor(c: Color): Color {
    val l = c.luminance()
    val black = Color(0xFF0D0D0D)
    val withBlack = (l + 0.05f) / (black.luminance() + 0.05f)
    val withWhite = 1.05f / (l + 0.05f)
    return if (withBlack >= withWhite) black else Color.White
}

/** The accent tuned to read as text on a page: darker on light pages, lighter on dark ones. */
private fun accentAsText(a: Color, dark: Boolean): Color {
    var c = a
    if (dark) {
        repeat(6) { if (c.luminance() < 0.16f) c = lerp(c, Color.White, 0.18f) }
    } else {
        repeat(8) { if (c.luminance() > 0.18f) c = lerp(c, Color.Black, 0.16f) }
    }
    return c
}

private fun ShieldColors.withAccent(a: Color) = copy(
    accent = a, accentText = accentAsText(a, dark), onAccent = onColor(a),
)

/** Resolves a theme and accent to colours (Material You keeps the wallpaper's own accent). */
@Composable
fun colorsFor(mode: ThemeMode, accent: Color = Electric): ShieldColors = when (mode) {
    ThemeMode.Default -> DefaultColors.withAccent(accent)
    ThemeMode.Light -> LightColors.withAccent(accent)
    // Follows the phone's dark mode: the soft grey dark at night, light by day.
    ThemeMode.System -> if (isSystemInDarkTheme()) SoftDarkColors.withAccent(accent) else LightColors.withAccent(accent)
    ThemeMode.You -> if (Build.VERSION.SDK_INT >= 31) {
        val ctx = LocalContext.current
        val dark = isSystemInDarkTheme()
        youColors(if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx), dark)
    } else DefaultColors.withAccent(accent)
}

@Composable
fun ShieldTheme(colors: ShieldColors, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalShieldColors provides colors) {
        MaterialTheme(colorScheme = schemeOf(colors), typography = type, content = content)
    }
}

/** Line icons from SVG paths (24×24), so nothing beyond material3 is needed. */
object Icons {
    private fun icon(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (p in paths) {
            b.addPath(
                addPathNodes(p), stroke = SolidColor(Color.White), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    val home = icon("home", "M3.5 10.5L12 4l8.5 6.5", "M5.5 9v10.5h13V9")
    val log = icon("log", "M8 6h12", "M8 12h12", "M8 18h12", "M4 6h.01", "M4 12h.01", "M4 18h.01")
    val rules = icon("rules", "M4 7h10", "M4 17h6", "M18 4v6", "M15 7h6", "M14 17h6")
    val apps = icon("apps", "M4 4h6v6H4z", "M14 4h6v6h-6z", "M4 14h6v6H4z", "M14 14h6v6h-6z")
    val settings = icon(
        "settings",
        "M19.26 9.75L21.86 10.31L21.86 13.69L19.26 14.25L18.72 15.55L20.17 17.77L17.77 20.17L15.55 18.72L14.25 19.26L13.69 21.86L10.31 21.86L9.75 19.26L8.45 18.72L6.23 20.17L3.83 17.77L5.28 15.55L4.74 14.25L2.14 13.69L2.14 10.31L4.74 9.75L5.28 8.45L3.83 6.23L6.23 3.83L8.45 5.28L9.75 4.74L10.31 2.14L13.69 2.14L14.25 4.74L15.55 5.28L17.77 3.83L20.17 6.23L18.72 8.45Z",
        "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
    )
    val search = icon("search", "M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z", "M20 20l-4-4")
    val plus = icon("plus", "M12 5v14", "M5 12h14")
    val close = icon("close", "M6 6l12 12", "M18 6L6 18")
    val trash = icon("trash", "M4 7h16", "M9 7V4h6v3", "M6 7l1 13h10l1-13")
    val chevron = icon("chevron", "M9 6l6 6-6 6")
    val filter = icon("filter", "M12 3.5l7.5 2.8v5.4c0 4.6-3.1 7.9-7.5 9.3-4.4-1.4-7.5-4.7-7.5-9.3V6.3z")
    val check = icon("check", "M5 12.5l4.5 4.5L19 7")
    val data = icon("data", "M5 20V11", "M12 20V4", "M19 20V13")
    val signal = icon("signal", "M12 21V11", "M8.2 13.2a5 5 0 0 1 7.6 0", "M5.5 10.5a9 9 0 0 1 13 0", "M12 8h.01")
    val wifi = icon("wifi", "M3.5 9.5a13 13 0 0 1 17 0", "M7 13a8 8 0 0 1 10 0", "M10.5 16.5a3 3 0 0 1 3 0", "M12 20h.01")
}

/**
 * The SonderShield mark: a shield cut in two by a swoosh. Drawn as two vectors so each
 * piece takes its own tint (top = accent or off, bottom = text colour).
 */
object LogoParts {
    private const val SHIELD = "M50 4L92 18V52C92 78 74 97 50 106C26 97 8 78 8 52V18Z"
    private fun part(name: String, clip: String): ImageVector =
        ImageVector.Builder(name, 100.dp, 110.dp, 100f, 110f)
            .addGroup(name = name, clipPathData = addPathNodes(clip))
            .addPath(addPathNodes(SHIELD), fill = SolidColor(Color.White))
            .clearGroup()
            .build()

    val top = part("logoTop", "M0 0H100V30Q46 60 0 80Z")
    val bottom = part("logoBottom", "M0 88Q52 70 100 45V110H0Z")
}
