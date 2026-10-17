package com.verisonder.sondershield.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val cardShape = RoundedCornerShape(16.dp)

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier, action: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.text, modifier = Modifier.weight(1f))
        action()
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(), color = Palette.dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp, modifier = Modifier.padding(start = 22.dp, top = 22.dp, bottom = 8.dp),
    )
}

@Composable
fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .fillMaxWidth()
            .clip(cardShape)
            .background(Palette.card)
            .border(1.dp, Palette.line, cardShape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(14.dp),
        content = content,
    )
}

/** A label + value row inside a panel. */
@Composable
fun InfoRow(label: String, value: String, valueColor: Color = Palette.dim, onClick: (() -> Unit)? = null) {
    Panel(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Palette.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(value, color = valueColor, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (onClick != null) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.chevron, null, tint = Palette.dim, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun SwitchRow(title: String, sub: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Panel(onClick = if (enabled) ({ onChange(!checked) }) else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = if (enabled) Palette.text else Palette.dim, fontSize = 15.sp)
                if (!sub.isNullOrEmpty()) Text(sub, color = Palette.dim, fontSize = 12.sp, lineHeight = 16.sp)
            }
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = checked, onCheckedChange = onChange, enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Palette.bg, checkedTrackColor = Palette.accent,
                    uncheckedThumbColor = Palette.dim, uncheckedTrackColor = Palette.cardHi, uncheckedBorderColor = Palette.line,
                ),
            )
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) Palette.accentSoft else Color.Transparent)
            .border(1.dp, if (selected) Palette.accent.copy(alpha = 0.4f) else Palette.line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
    ) {
        Text(text, color = if (selected) Palette.accent else Palette.dim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    onDone: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder, color = Palette.dim, fontSize = 14.sp) },
        leadingIcon = leading?.let { { Icon(it, null, tint = Palette.dim, modifier = Modifier.size(18.dp)) } },
        singleLine = singleLine,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.accent.copy(alpha = 0.6f), unfocusedBorderColor = Palette.line,
            focusedContainerColor = Palette.card, unfocusedContainerColor = Palette.card,
            cursorColor = Palette.accent, focusedTextColor = Palette.text, unfocusedTextColor = Palette.text,
        ),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp),
        modifier = modifier,
    )
}

@Composable
fun AccentButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 12.dp)) {
        Text(text, color = if (enabled) Palette.accent else Palette.dim, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Dot(color: Color) {
    Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
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
