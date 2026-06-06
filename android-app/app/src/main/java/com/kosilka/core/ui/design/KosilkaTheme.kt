package com.kosilka.core.ui.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF256D3F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB8F1C8),
    onPrimaryContainer = Color(0xFF00210D),
    secondary = Color(0xFF4F6353),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD2E8D3),
    onSecondaryContainer = Color(0xFF0D1F13),
    tertiary = Color(0xFF3E6472),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC1E9FA),
    onTertiaryContainer = Color(0xFF001F29),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    background = Color(0xFFF6FBF4),
    onBackground = Color(0xFF191D19),
    surface = Color(0xFFF6FBF4),
    onSurface = Color(0xFF191D19),
    surfaceVariant = Color(0xFFDEE5DB),
    onSurfaceVariant = Color(0xFF424940),
    outline = Color(0xFF727970)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF9CD4AD),
    onPrimary = Color(0xFF00391D),
    primaryContainer = Color(0xFF07522E),
    onPrimaryContainer = Color(0xFFB8F1C8),
    secondary = Color(0xFFB6CCB8),
    onSecondary = Color(0xFF223527),
    secondaryContainer = Color(0xFF384B3D),
    onSecondaryContainer = Color(0xFFD2E8D3),
    tertiary = Color(0xFFA5CDDD),
    onTertiary = Color(0xFF053543),
    tertiaryContainer = Color(0xFF244C5A),
    onTertiaryContainer = Color(0xFFC1E9FA),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    background = Color(0xFF101512),
    onBackground = Color(0xFFDFE4DC),
    surface = Color(0xFF101512),
    onSurface = Color(0xFFDFE4DC),
    surfaceVariant = Color(0xFF424940),
    onSurfaceVariant = Color(0xFFC2C9BF),
    outline = Color(0xFF8C9389)
)

@Immutable
data class KosilkaSpacing(
    val xSmall: Int,
    val small: Int,
    val medium: Int,
    val large: Int,
    val xLarge: Int
)

private val DefaultSpacing = KosilkaSpacing(
    xSmall = 4,
    small = 8,
    medium = 12,
    large = 16,
    xLarge = 24
)

val LocalKosilkaSpacing = staticCompositionLocalOf { DefaultSpacing }

object KosilkaDesign {
    val spacing: KosilkaSpacing
        @Composable get() = LocalKosilkaSpacing.current
}

@Composable
fun KosilkaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        DarkColorScheme
    } else {
        LightColorScheme
    }

    CompositionLocalProvider(LocalKosilkaSpacing provides DefaultSpacing) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}