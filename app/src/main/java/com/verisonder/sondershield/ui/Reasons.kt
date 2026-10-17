package com.verisonder.sondershield.ui

import android.content.Context
import com.verisonder.sondershield.data.Prefs

/** The engine's reason, readable: "list:stevenblack" -> "StevenBlack Unified". */
fun reasonLabel(ctx: Context, reason: String): String {
    if (!reason.startsWith("list:")) return reason
    val id = reason.removePrefix("list:")
    return Prefs(ctx).allLists().firstOrNull { it.id == id }?.name ?: id
}

fun isListReason(reason: String) = reason.startsWith("list:") || reason == "block list"
