package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
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

@Composable
fun C3StreamerTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = AmoledDarkColorScheme,
        typography = AppTypography,
        content = content
    )
}
