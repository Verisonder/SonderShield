package com.verisonder.sondershield.ui

import android.content.Context
import com.verisonder.sondershield.data.Prefs

/** The engine's reason, readable: "list:stevenblack" -> "StevenBlack Unified", rules in the app's language. */
fun reasonLabel(ctx: Context, reason: String): String {
    if (reason.startsWith("list:")) {
        val id = reason.removePrefix("list:")
        return Prefs(ctx).allLists().firstOrNull { it.id == id }?.name ?: id
    }
    return when (reason) {
        "your allow rule" -> trc(ctx, "your allow rule", "votre règle d'autorisation")
        "your block rule" -> trc(ctx, "your block rule", "votre règle de blocage")
        "list exception" -> trc(ctx, "list exception", "exception d'une liste")
        "block list" -> trc(ctx, "block list", "liste de blocage")
        "cached" -> trc(ctx, "cached", "en cache")
        else -> reason
    }
}

fun isListReason(reason: String) = reason.startsWith("list:") || reason == "block list"
