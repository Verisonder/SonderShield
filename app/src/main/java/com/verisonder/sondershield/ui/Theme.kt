package com.verisonder.sondershield.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** SonderShield's look: deep slate, one teal accent, red only for "blocked". */
object Palette {
    val bg = Color(0xFF0E1116)
    val card = Color(0xFF161B22)
    val cardHi = Color(0xFF1C232C)
    val line = Color(0xFF232A33)
    val text = Color(0xFFE6EDF3)
    val dim = Color(0xFF8B97A5)
    val accent = Color(0xFF2EE6C5)
    val accentSoft = Color(0x222EE6C5)
    val blocked = Color(0xFFFF6B6B)
    val warn = Color(0xFFFFC857)
    val off = Color(0xFF4A5563)
}

private val scheme = darkColorScheme(
    primary = Palette.accent,
    onPrimary = Color(0xFF00201A),
    primaryContainer = Palette.accentSoft,
    onPrimaryContainer = Palette.accent,
    secondary = Palette.accent,
    onSecondary = Color(0xFF00201A),
    background = Palette.bg,
    onBackground = Palette.text,
    surface = Palette.bg,
    onSurface = Palette.text,
    surfaceVariant = Palette.card,
    onSurfaceVariant = Palette.dim,
    surfaceContainer = Palette.card,
    surfaceContainerHigh = Palette.cardHi,
    surfaceContainerHighest = Palette.cardHi,
    surfaceContainerLow = Palette.card,
    surfaceContainerLowest = Palette.bg,
    outline = Palette.line,
    outlineVariant = Palette.line,
    error = Palette.blocked,
)

private val type = Typography().let { t ->
    t.copy(
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

val numberStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.text)

@Composable
fun ShieldTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
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

    val shield = icon("shield", "M12 2.5l7.5 2.8v5.9c0 4.7-3.2 8.8-7.5 10.3-4.3-1.5-7.5-5.6-7.5-10.3V5.3z")
    val shieldCheck = icon("shieldCheck", "M12 2.5l7.5 2.8v5.9c0 4.7-3.2 8.8-7.5 10.3-4.3-1.5-7.5-5.6-7.5-10.3V5.3z", "M8.6 12l2.4 2.4 4.4-4.8")
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
