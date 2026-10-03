package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AmoledDarkColorScheme = darkColorScheme(
    primary = GlowingGray,
    onPrimary = OnGlowingGray,
    primaryContainer = GlowingGrayContainer,
    onPrimaryContainer = GlowingGrayLight,
    secondary = SlateSilver,
    onSecondary = Color(0xFF101217),
    secondaryContainer = SlateSilverContainer,
    onSecondaryContainer = GlowingGrayLight,
    tertiary = StreamEmerald,
    onTertiary = Color(0xFF0B2115),
    tertiaryContainer = StreamEmeraldContainer,
    onTertiaryContainer = StreamEmerald,
    background = AmoledBlack,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = DarkCardBorder,
    outlineVariant = Color(0xFF1E2430),
    error = ErrorCoral,
    onError = Color.Black
)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color.White,
    secondary = LightSecondary,
    onSecondary = Color.White,
    background = LightBackground,
    onBackground = Color(0xFF0F172A),
    surface = LightSurface,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    error = ErrorCoral
)

@Composable
fun C3StreamerTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) AmoledDarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
