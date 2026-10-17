package com.verisonder.sondershield.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Two languages, written side by side where the text is used: tr("Log", "Journal").
 * Follows the phone's language, or the per-app language set in Android settings.
 */
@Composable
@ReadOnlyComposable
fun tr(en: String, fr: String): String = if (isFrench()) fr else en

@Composable
@ReadOnlyComposable
fun isFrench(): Boolean = LocalConfiguration.current.locales[0].language == "fr"

/** For code outside composables (service, notification, toasts). */
fun trc(ctx: Context, en: String, fr: String): String =
    if (ctx.resources.configuration.locales[0].language == "fr") fr else en
