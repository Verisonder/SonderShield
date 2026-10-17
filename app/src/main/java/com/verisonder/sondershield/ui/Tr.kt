package com.verisonder.sondershield.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Three languages, written side by side where the text is used:
 * tr("Log", "Journal", "Protokoll"). Follows the phone's language, or the per-app language
 * set in Android settings. Anything else gets English.
 */
@Composable
@ReadOnlyComposable
fun tr(en: String, fr: String, de: String): String = when (lang()) {
    "fr" -> fr
    "de" -> de
    else -> en
}

/** "fr", "de" or "en". */
@Composable
@ReadOnlyComposable
fun lang(): String = pick(LocalConfiguration.current.locales[0].language)

@Composable
@ReadOnlyComposable
fun isFrench(): Boolean = lang() == "fr"

private fun pick(l: String) = when (l) {
    "fr", "de" -> l
    else -> "en"
}

fun langOf(ctx: Context): String = pick(ctx.resources.configuration.locales[0].language)

/** For code outside composables (service, notification, toasts). */
fun trc(ctx: Context, en: String, fr: String, de: String): String = when (langOf(ctx)) {
    "fr" -> fr
    "de" -> de
    else -> en
}
