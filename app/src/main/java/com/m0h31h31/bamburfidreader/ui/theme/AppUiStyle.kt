package com.m0h31h31.bamburfidreader.ui.theme

import androidx.compose.runtime.compositionLocalOf

enum class AppUiStyle {
    NEUMORPHIC,
    MIUIX
}

enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM
}

val LocalAppUiStyle = compositionLocalOf { AppUiStyle.NEUMORPHIC }
