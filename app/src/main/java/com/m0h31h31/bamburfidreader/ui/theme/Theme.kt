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
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDarkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColorScheme

private val ColorError = Coral.copy(alpha = 0.95f)

private val LightColorScheme = lightColorScheme(
    primary = Ocean,
    onPrimary = Mist,
    secondary = Mint,
    tertiary = Coral,
    background = Mist,
    surface = Frost,
    surfaceVariant = Cloud,
    onBackground = Ink,
    onSurface = Ink,
    onSurfaceVariant = Steel,
    outline = Cloud,
    outlineVariant = Steel.copy(alpha = 0.45f),
    error = ColorError,
    errorContainer = Coral.copy(alpha = 0.18f),
    onErrorContainer = Ink
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkOcean,
    onPrimary = DarkMist,
    secondary = Mint,
    background = DarkMist,
    surface = DarkFrost,
    surfaceVariant = DarkCloud,
    onBackground = DarkInk,
    onSurface = DarkInk,
    onSurfaceVariant = DarkSteel,
    outline = DarkCloud,
    outlineVariant = DarkSteel.copy(alpha = 0.35f),
    error = ColorError
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(14.dp),
    small = RoundedCornerShape(18.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(30.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

private val MiuixLightColorScheme = lightColorScheme(
    primary = BlueGrey,
    onPrimary = Mist,
    secondary = Ocean,
    tertiary = Mint,
    background = ColorWhiteAlt,
    surface = ColorWhite,
    surfaceVariant = ColorWhite,
    onBackground = Ink,
    onSurface = Ink,
    onSurfaceVariant = Steel,
    outline = Cloud,
    outlineVariant = Cloud.copy(alpha = 0.8f),
    error = ColorError,
    errorContainer = Coral.copy(alpha = 0.14f),
    onErrorContainer = Ink
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

private val MiuixDarkColorScheme = darkColorScheme(
    primary = DarkOcean,
    onPrimary = DarkMist,
    secondary = Mint,
    background = DarkMist,
    surface = DarkFrost,
    surfaceVariant = DarkCloud,
    onBackground = DarkInk,
    onSurface = DarkInk,
    onSurfaceVariant = DarkSteel,
    outline = DarkCloud,
    outlineVariant = DarkSteel.copy(alpha = 0.35f),
    error = ColorError
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
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = when (uiStyle) {
        AppUiStyle.NEUMORPHIC -> if (darkTheme) DarkColorScheme else LightColorScheme
        AppUiStyle.MIUIX -> if (darkTheme) MiuixDarkColorScheme else MiuixLightColorScheme
    }
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
