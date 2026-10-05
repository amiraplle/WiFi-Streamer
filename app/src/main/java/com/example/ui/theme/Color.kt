package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// AMOLED / Matte Dark Studio Palette (matching C3 music interface)
val AmoledBlack = Color(0xFF000000)
val DarkBackground = Color(0xFF0B0B0E)
val DarkSurface = Color(0xFF15151B)
val DarkSurfaceVariant = Color(0xFF1C1C24)
val DarkCardBorder = Color(0xFF24242E)

// Inset data containers & pill buttons
val MatteDarkInset = Color(0xFF101014)
val MatteDarkInsetBorder = Color(0xFF1E1E26)
val MatteDarkPill = Color(0xFF1A1A22)
val MatteDarkPillBorder = Color(0xFF2A2A38)

// Aesthetic Minimal Glowing Gray / Silver
val GlowingGray = Color(0xFFCDD2DA)
val GlowingGrayLight = Color(0xFFFFFFFF)
val GlowingGrayDark = Color(0xFF8E8E98)
val GlowingGrayContainer = Color(0xFF202028)
val OnGlowingGray = Color(0xFF0B0B0E)

// Secondary subtle slate/silver
val SlateSilver = Color(0xFF94A3B8)
val SlateSilverContainer = Color(0xFF181820)

// Legacy alias mappings for backward compatibility
val ElectricCyan = GlowingGray
val ElectricCyanDark = GlowingGrayDark
val ElectricCyanContainer = GlowingGrayContainer
val OnElectricCyan = OnGlowingGray

val NeonViolet = SlateSilver
val NeonVioletDark = Color(0xFF242430)
val NeonVioletContainer = SlateSilverContainer

// Soft, aesthetic streaming indicator (subtle C3 green capsule & dot)
val StreamEmerald = Color(0xFF4ADE80)
val StreamEmeraldContainer = Color(0xFF112618)

// Low-contrast subdued warnings/errors (Yellow replaced with neutral slate gray)
val WarningAmber = Color(0xFF94A3B8) // Replaced yellow with refined slate gray
val ErrorCoral = Color(0xFFE06C75)

val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFF8E8E98)
val TextTertiary = Color(0xFF5D6370)

// Light Palette (Clean minimalist fallback)
val LightBackground = Color(0xFFF8FAFC)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFEEF2F6)
val LightPrimary = Color(0xFF475569)
val LightSecondary = Color(0xFF64748B)
