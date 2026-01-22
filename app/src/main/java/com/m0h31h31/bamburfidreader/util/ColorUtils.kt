package com.m0h31h31.bamburfidreader.util

import androidx.compose.ui.graphics.Color
import java.util.Locale

fun normalizeColorValue(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) {
        return ""
    }
    return if (trimmed.startsWith("#")) {
        "#" + trimmed.substring(1).uppercase(Locale.US)
    } else {
        "#" + trimmed.uppercase(Locale.US)
    }
}

fun parseColorValue(value: String): Color? {
    val normalized = normalizeColorValue(value)
    val hex = normalized.removePrefix("#")
    return try {
        val argb = when (hex.length) {
            8 -> {
                val r = hex.substring(0, 2).toInt(16)
                val g = hex.substring(2, 4).toInt(16)
                val b = hex.substring(4, 6).toInt(16)
                val a = hex.substring(6, 8).toInt(16)
                (a shl 24) or (r shl 16) or (g shl 8) or b
            }

            6 -> {
                val r = hex.substring(0, 2).toInt(16)
                val g = hex.substring(2, 4).toInt(16)
                val b = hex.substring(4, 6).toInt(16)
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }

            else -> return null
        }
        Color(argb)
    } catch (_: Exception) {
        null
    }
}
