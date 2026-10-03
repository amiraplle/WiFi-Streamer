package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// AMOLED Dark Palette
val AmoledBlack = Color(0xFF000000)
val DarkBackground = Color(0xFF080A0E)
val DarkSurface = Color(0xFF11141A)
val DarkSurfaceVariant = Color(0xFF191D26)
val DarkCardBorder = Color(0xFF252B37)

// Aesthetic Glowing Gray Highlights (Subtle, soft luminous metallic gray, non-glaring)
val GlowingGray = Color(0xFFCDD2DA)           // Soft luminous glowing platinum gray
val GlowingGrayLight = Color(0xFFE5E9F0)      // Highlight edge
val GlowingGrayDark = Color(0xFF88909E)       // Muted gray
val GlowingGrayContainer = Color(0xFF232832) // Low-glare dark surface container with subtle glow
val OnGlowingGray = Color(0xFF0E1116)        // High contrast text/icon on glowing gray

// Secondary subtle slate/silver
val SlateSilver = Color(0xFFA5ADB9)
val SlateSilverContainer = Color(0xFF1C2028)

// Legacy alias mappings for backward compatibility
val ElectricCyan = GlowingGray
val ElectricCyanDark = GlowingGrayDark
val ElectricCyanContainer = GlowingGrayContainer
val OnElectricCyan = OnGlowingGray

val NeonViolet = SlateSilver
val NeonVioletDark = Color(0xFF2F3542)
val NeonVioletContainer = SlateSilverContainer

// Soft, aesthetic streaming indicator (calm, non-glaring sage emerald)
val StreamEmerald = Color(0xFF6ECBA0)
val StreamEmeraldContainer = Color(0xFF152A20)

// Subdued warnings/errors
val WarningAmber = Color(0xFFE2B855)
val ErrorCoral = Color(0xFFDF6B75)

val TextPrimary = Color(0xFFECEFF4)
val TextSecondary = Color(0xFF9098A5)
val TextTertiary = Color(0xFF5D6574)

// Light Palette (Clean minimalist fallback)
val LightBackground = Color(0xFFF8FAFC)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFEEF2F6)
val LightPrimary = Color(0xFF475569)
val LightSecondary = Color(0xFF64748B)
