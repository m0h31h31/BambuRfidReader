package com.m0h31h31.bamburfidreader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDarkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColorScheme

private val ColorError = Coral.copy(alpha = 0.95f)

private fun lum(c: Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

private data class PSpec(val l: Color, val d: Color)

private val paletteSpecs: Map<ColorPalette, PSpec> = mapOf(
    ColorPalette.ORANGE      to PSpec(Color(0xFFF99963), Color(0xFFF5AA7F)),
    ColorPalette.SKY_BLUE    to PSpec(Color(0xFF56B7E6), Color(0xFF7AC5EC)),
    ColorPalette.OCEAN       to PSpec(Color(0xFF0078BF), Color(0xFF4AAED6)),
    ColorPalette.ICE_BLUE    to PSpec(Color(0xFFA3D8E1), Color(0xFFA3D8E1)),
    ColorPalette.NIGHT_BLUE  to PSpec(Color(0xFF042F56), Color(0xFF4A7AA8)),
    ColorPalette.GUN_GRAY    to PSpec(Color(0xFF757575), Color(0xFF9A9A9A)),
    ColorPalette.ROCK_GRAY   to PSpec(Color(0xFF9B9EA0), Color(0xFFB5B8BA)),
    ColorPalette.FRUIT_GREEN to PSpec(Color(0xFFC2E189), Color(0xFFC2E189)),
    ColorPalette.GRASS_GREEN to PSpec(Color(0xFF61C680), Color(0xFF7DD494)),
    ColorPalette.NIGHT_GREEN to PSpec(Color(0xFF68724D), Color(0xFF92A070)),
    ColorPalette.CHARCOAL    to PSpec(Color(0xFF000000), Color(0xFF686868)),
    ColorPalette.DARK_BROWN  to PSpec(Color(0xFF4D3324), Color(0xFF8A6350)),
    ColorPalette.LATTE       to PSpec(Color(0xFFD3B7A7), Color(0xFFD3B7A7)),
    ColorPalette.NIGHT_BROWN to PSpec(Color(0xFF7D6556), Color(0xFFA88A7C)),
    ColorPalette.SAND_BROWN  to PSpec(Color(0xFFAE835B), Color(0xFFC4A07E)),
    ColorPalette.SAKURA      to PSpec(Color(0xFFE8AFCF), Color(0xFFE8AFCF)),
    ColorPalette.LILAC       to PSpec(Color(0xFFAE96D4), Color(0xFFC4B0E0)),
    ColorPalette.CRIMSON     to PSpec(Color(0xFFDE4343), Color(0xFFE87070)),
    ColorPalette.BRICK_RED   to PSpec(Color(0xFFB15533), Color(0xFFCC7A55)),
    ColorPalette.BERRY       to PSpec(Color(0xFF950051), Color(0xFFC44082)),
    ColorPalette.NIGHT_RED   to PSpec(Color(0xFFBB3D43), Color(0xFFD46B70)),
    ColorPalette.IVORY       to PSpec(Color(0xFFFFFFFF), Color(0xFFE0DDD8)),
    ColorPalette.BONE        to PSpec(Color(0xFFCBC6B8), Color(0xFFCBC6B8)),
    ColorPalette.LEMON       to PSpec(Color(0xFFF7D959), Color(0xFFF7D959)),
    ColorPalette.DESERT      to PSpec(Color(0xFFE8DBB7), Color(0xFFE8DBB7)),
)

private fun buildLightScheme(primary: Color, miuix: Boolean) = lightColorScheme(
    primary = primary,
    onPrimary = if (lum(primary) > 0.4f) Ink else Color.White,
    secondary = Mint, tertiary = Coral,
    background = if (miuix) ColorWhiteAlt else lerp(Mist, primary, 0.05f),
    surface = if (miuix) ColorWhite else lerp(Frost, primary, 0.08f),
    surfaceVariant = if (miuix) ColorWhite else lerp(Cloud, primary, 0.15f),
    onBackground = Ink, onSurface = Ink, onSurfaceVariant = Steel,
    outline = if (miuix) Cloud else lerp(Cloud, primary, 0.18f),
    outlineVariant = Steel.copy(alpha = 0.45f),
    error = ColorError, errorContainer = Coral.copy(alpha = 0.18f), onErrorContainer = Ink
)

private fun buildDarkScheme(primary: Color) = darkColorScheme(
    primary = primary,
    onPrimary = if (lum(primary) > 0.4f) DarkMist else DarkInk,
    secondary = Mint,
    background = DarkMist, surface = DarkFrost, surfaceVariant = DarkCloud,
    onBackground = DarkInk, onSurface = DarkInk, onSurfaceVariant = DarkSteel,
    outline = DarkCloud, outlineVariant = DarkSteel.copy(alpha = 0.35f),
    error = ColorError
)

private fun paletteColorScheme(
    palette: ColorPalette, miuix: Boolean, dark: Boolean
): androidx.compose.material3.ColorScheme {
    val spec = paletteSpecs[palette] ?: paletteSpecs[ColorPalette.OCEAN]!!
    val primary = if (dark) spec.d else spec.l
    return if (dark) buildDarkScheme(primary) else buildLightScheme(primary, miuix)
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(14.dp),
    small = RoundedCornerShape(18.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(30.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

private val MiuixLightColors = miuixLightColorScheme(
    background = ColorWhiteAlt,
    surface = ColorWhite,
    surfaceVariant = ColorWhite,
    surfaceContainer = ColorWhite,
    surfaceContainerHigh = Color(0xFFF2F4F7),
    surfaceContainerHighest = Color(0xFFECEFF4),
    dividerLine = Cloud.copy(alpha = 0.65f),
    onSurface = Ink.copy(alpha = 0.92f),
    onSurfaceContainer = Color(0xFF6C7B90),
    onSurfaceContainerVariant = Color(0xFFA6B0BE),
    onSurfaceContainerHigh = Color(0xFFB0B8C3),
    onSurfaceContainerHighest = Color(0xFF667589),
)

private val MiuixDarkColors = miuixDarkColorScheme(
    onSurfaceContainer = DarkInk.copy(alpha = 0.92f),
    onSurfaceContainerVariant = DarkSteel.copy(alpha = 0.78f)
)

@Composable
fun BambuRfidReaderTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    uiStyle: AppUiStyle = AppUiStyle.NEUMORPHIC,
    colorPalette: ColorPalette = ColorPalette.OCEAN,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = paletteColorScheme(colorPalette, miuix = uiStyle == AppUiStyle.MIUIX, dark = darkTheme)
    CompositionLocalProvider(LocalAppUiStyle provides uiStyle) {
        if (uiStyle == AppUiStyle.MIUIX) {
            MiuixTheme(
                colors = if (darkTheme) MiuixDarkColors else MiuixLightColors
            ) {
                MaterialTheme(
                    colorScheme = colorScheme,
                    typography = Typography,
                    shapes = AppShapes,
                    content = content
                )
            }
        } else {
            MaterialTheme(
                colorScheme = colorScheme,
                typography = Typography,
                shapes = AppShapes,
                content = content
            )
        }
    }
}
