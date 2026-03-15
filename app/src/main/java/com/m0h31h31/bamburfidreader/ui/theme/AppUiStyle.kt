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

enum class ColorPalette {
    ORANGE,       // 橘橙
    SKY_BLUE,     // 天蓝色
    OCEAN,        // 海蓝  ← 保持默认值向后兼容
    ICE_BLUE,     // 冰蓝
    NIGHT_BLUE,   // 暗夜蓝
    GUN_GRAY,     // 枪灰色
    ROCK_GRAY,    // 岩石灰
    FRUIT_GREEN,  // 果绿色
    GRASS_GREEN,  // 草绿
    NIGHT_GREEN,  // 暗夜绿
    CHARCOAL,     // 炭黑
    DARK_BROWN,   // 深棕色
    LATTE,        // 拿铁褐
    NIGHT_BROWN,  // 暗夜棕
    SAND_BROWN,   // 沙棕色
    SAKURA,       // 樱花粉
    LILAC,        // 丁香紫
    CRIMSON,      // 猩红
    BRICK_RED,    // 砖红色
    BERRY,        // 莓果紫
    NIGHT_RED,    // 暗夜红
    IVORY,        // 象牙白
    BONE,         // 骨白
    LEMON,        // 柠檬黄
    DESERT        // 沙漠黄
}

val LocalAppUiStyle = compositionLocalOf { AppUiStyle.NEUMORPHIC }
