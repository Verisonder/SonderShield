package com.verisonder.sondershield.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val blockShape = RoundedCornerShape(18.dp)
val Gutter = 22.dp

/** "▲ LABEL": small uppercase label with a triangle, in the accent. */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier, color: Color = Palette.accentText) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp, 9.dp)) {
            val p = Path().apply { moveTo(size.width / 2, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
            drawPath(p, color)
        }
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), color = color, fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.sp)
    }
}

/** Screen title: optional kicker, big Unbounded word, optional line under it. */
@Composable
fun ScreenHeader(
    title: String,
    kicker: String? = null,
    sub: String? = null,
    action: @Composable RowScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(start = Gutter, end = 12.dp, top = 22.dp, bottom = 6.dp)) {
        if (kicker != null) Kicker(kicker, Modifier.padding(bottom = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = display(40), color = Palette.text, modifier = Modifier.weight(1f))
            action()
        }
        if (sub != null) Text(sub, color = Palette.dim, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp, end = 10.dp))
    }
}

/** A numbered section: thick line on top, "01" in the accent, a title. */
@Composable
fun Section(number: Int, title: String, first: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Gutter).padding(top = 24.dp)) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(if (first) Palette.accent else Palette.text))
        Text("%02d".format(number), style = display(15), color = Palette.accentText, modifier = Modifier.padding(top = 14.dp))
        Text(title, color = Palette.text, fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 19.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
        content()
    }
}

/** Rounded block: the building piece of every card. */
@Composable
fun Block(
    modifier: Modifier = Modifier,
    color: Color = Palette.panel,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    Column(
        modifier.fillMaxWidth().let { if (onClick != null) it.pressScale(src) else it }.clip(blockShape).background(color)
            .let { if (onClick != null) it.clickable(interactionSource = src, indication = ripple(), onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        content = content,
    )
}

/** Card with a title, a line under it and something on the right. Picked = filled with the accent. */
@Composable
fun OptionCard(
    title: String,
    sub: String? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    subColor: Color? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val bg by animateColorAsState(if (selected) Palette.accent else Palette.panel, tween(240), label = "card")
    val fg by animateColorAsState(if (selected) Palette.onAccent else Palette.text, tween(240), label = "cardText")
    Block(Modifier.padding(bottom = 8.dp), color = bg, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = fg, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                if (!sub.isNullOrEmpty()) {
                    Text(
                        sub, color = subColor ?: if (selected) fg.copy(alpha = 0.8f) else Palette.dim,
                        fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            trailing()
        }
    }
}

/** On/off switch in the house style: accent track with a dark knob when on. */
@Composable
fun Toggle(checked: Boolean, onDark: Boolean = false, onChange: (Boolean) -> Unit) {
    val track by animateColorAsState(
        when {
            checked && onDark -> Palette.onAccent
            checked -> Palette.accent
            else -> Palette.card2
        }, label = "track",
    )
    val knob = when {
        checked && onDark -> Palette.accent
        checked -> Palette.onAccent
        else -> Palette.faint
    }
    val x by animateDpAsState(if (checked) 21.dp else 3.dp, spring(dampingRatio = 0.6f, stiffness = 700f), label = "knob")
    Box(
        Modifier.padding(start = 10.dp).size(46.dp, 28.dp).clip(CircleShape).background(track)
            .clickable { onChange(!checked) },
    ) {
        Box(Modifier.offset(x = x, y = 3.dp).size(22.dp).clip(CircleShape).background(knob))
    }
}

/** Title + line + toggle, the whole card clickable. */
@Composable
fun ToggleCard(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    OptionCard(title, sub, onClick = { onChange(!checked) }) { Toggle(checked, onChange = onChange) }
}

/** Filter pill. Picked = solid (white on dark themes). */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Palette.pill else Color.Transparent, tween(200), label = "chip")
    val edge by animateColorAsState(if (selected) Palette.pill else Palette.line, tween(200), label = "chipEdge")
    val fg by animateColorAsState(if (selected) Palette.onPill else Palette.text, tween(200), label = "chipText")
    Box(
        Modifier.height(36.dp).pressScale().clip(CircleShape)
            .background(bg)
            .border(1.5.dp, edge, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

/** Shrinks a little while pressed and springs back: a tactile tap on cards and pills. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource? = null): Modifier {
    val src = source ?: remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.5f, stiffness = 900f), label = "press")
    return this.scale(s)
}

@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.padding(horizontal = Gutter, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** Pill button: filled with the accent, or outlined. */
@Composable
fun PillButton(text: String, filled: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = CircleShape
    Box(
        Modifier.height(44.dp).clip(shape)
            .background(if (filled && enabled) Palette.accent else Color.Transparent)
            .border(2.dp, if (filled && enabled) Palette.accent else if (enabled) Palette.text else Palette.line, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            color = when { !enabled -> Palette.faint; filled -> Palette.onAccent; else -> Palette.text },
        )
    }
}

/** Text field as a rounded block, with an optional icon in front. */
@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    onDone: (() -> Unit)? = null,
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = Palette.text, fontFamily = Body, fontSize = 15.sp),
        cursorBrush = SolidColor(Palette.accent),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = modifier,
        decorationBox = { inner ->
            Row(
                Modifier.height(48.dp).clip(RoundedCornerShape(14.dp)).background(Palette.panel).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) {
                    Icon(leading, null, tint = Palette.faint, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, color = Palette.faint, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            }
        },
    )
}

/** The SonderShield mark. [lit]: top piece in the accent; otherwise grey. */
@Composable
fun Logo(size: Dp, lit: Boolean, modifier: Modifier = Modifier, split: Float = 0f, top: Color? = null, bottom: Color? = null) {
    val topColor by animateColorAsState(top ?: if (lit) Palette.accent else Palette.off, tween(420), label = "logoTop")
    // split: the two pieces move apart along the cut (0 = closed).
    val d = size * 0.07f * split
    Box(modifier.size(size, size * 1.1f)) {
        Image(LogoParts.top, null, Modifier.matchParentSize().offset(x = -d * 0.6f, y = -d), colorFilter = ColorFilter.tint(topColor))
        Image(LogoParts.bottom, null, Modifier.matchParentSize().offset(x = d * 0.6f, y = d), colorFilter = ColorFilter.tint(bottom ?: Palette.text))
    }
}

/** Wordmark: "Sonder" in the text colour, "Shield" in the accent. */
@Composable
fun Wordmark(size: Int = 19) {
    Row {
        Text("Sonder", style = display(size), color = Palette.text)
        Text("Shield", style = display(size), color = Palette.accentText)
    }
}

/**
 * The swoosh between the page and the section below: a thin accent blade over the curved
 * top edge of [below]. Full width.
 */
@Composable
fun Swoosh(blade: Color, below: Color, modifier: Modifier = Modifier, progress: Float = 1f) {
    Canvas(modifier.fillMaxWidth().height(42.dp)) {
        val w = size.width
        val h = size.height
        val sx = w / 360f
        val sy = h / 46f
        fun o(x: Float, y: Float) = Offset(x * sx, y * sy)
        val bladePath = Path().apply {
            moveTo(o(0f, 46f).x, o(0f, 46f).y)
            quadraticTo(o(130f, 4f).x, o(130f, 4f).y, o(360f, 12f).x, o(360f, 12f).y)
            lineTo(o(360f, 20f).x, o(360f, 20f).y)
            quadraticTo(o(155f, 16f).x, o(155f, 16f).y, o(0f, 46f).x, o(0f, 46f).y)
            close()
        }
        val fill = Path().apply {
            moveTo(o(0f, 46f).x, o(0f, 46f).y)
            quadraticTo(o(150f, 20f).x, o(150f, 20f).y, o(360f, 28f).x, o(360f, 28f).y)
            lineTo(w, h + 1f)
            lineTo(0f, h + 1f)
            close()
        }
        // The blade sweeps in from the left as protection turns on.
        clipRect(right = w * progress) { drawPath(bladePath, blade) }
        drawPath(fill, below)
    }
}

/**
 * One step of the offset "ladder": big number, two-line label. The last one is filled.
 * Slides in from the right (staggered by [index]) and counts up to [target].
 */
@Composable
fun LadderStep(target: Double, format: (Double) -> String, label: String, index: Int, onPanel: Boolean) {
    val last = index == 2
    val bg = when { last -> Palette.accent; onPanel -> Palette.card2; else -> Palette.panel }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(90L * index)
        enter.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 260f))
    }
    val count = remember { Animatable(0f) }
    LaunchedEffect(target) { count.animateTo(target.toFloat(), tween(if (count.value == 0f) 1100 else 500, easing = FastOutSlowInEasing)) }
    Row(
        Modifier.padding(start = (18 * index).dp).fillMaxWidth()
            .graphicsLayer { translationX = (1f - enter.value) * 120.dp.toPx(); alpha = enter.value.coerceIn(0f, 1f) }
            .clip(blockShape).background(bg)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            format(count.value.toDouble()), style = display(26), color = if (last) Palette.onAccent else Palette.accentText,
            maxLines = 1, modifier = Modifier.widthIn(min = 92.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(label, color = if (last) Palette.onAccent else Palette.dim, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = if (last) FontWeight.Medium else FontWeight.Normal)
    }
}

/** Small round button with an icon. */
@Composable
fun IconPill(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).border(1.5.dp, Palette.line, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = Palette.text, modifier = Modifier.size(19.dp)) }
}

fun Long.grouped(): String = "%,d".format(this)
fun Int.grouped(): String = "%,d".format(this)

fun ago(ms: Long): String {
    if (ms <= 0) return "never"
    val s = (System.currentTimeMillis() - ms) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> "${s / 86400} d ago"
    }
}
