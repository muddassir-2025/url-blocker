package com.muddassir.clearview.ui.theme

import androidx.compose.ui.graphics.Color

// ── Restrained Dark Palette (YouTube / Instagram inspired) ────
val DarkBase = Color(0xFF0F0F0F)
val DarkSurface = Color(0xFF181818)
val DarkCard = Color(0xFF212121)
val DarkCardElevated = Color(0xFF282828)
val DarkBorder = Color(0xFF2E2E2E)
val DarkDivider = Color(0xFF222222)

// Aliases for compatibility
val ObsidianBase = DarkBase
val ObsidianSurface = DarkSurface
val ObsidianCard = DarkCard
val ObsidianCardElevated = DarkCardElevated
val ObsidianBorder = DarkBorder
val ObsidianSubtle = DarkDivider

// ── Restrained Accent Color (One Primary Accent) ───────────────
val BrandAccent = Color(0xFF10B981)
val EmeraldPrimary = BrandAccent
val EmeraldOnPrimary = Color(0xFFFFFFFF)
val EmeraldContainer = Color(0xFF064E3B)
val EmeraldOnContainer = Color(0xFFA7F3D0)
val EmeraldLight = Color(0xFF34D399)

val CyanSecondary = Color(0xFF06B6D4)
val CyanContainer = Color(0xFF164E63)
val CyanOnContainer = Color(0xFFCFFAFE)

val AmberGold = Color(0xFFF59E0B)
val AmberContainer = Color(0xFF78350F)
val AmberOnContainer = Color(0xFFFDE68A)

// ── Text Hierarchy ────────────────────────────────────────────
val TextPrimary = Color(0xFFF1F1F1)
val TextSecondary = Color(0xFFAAAAAA)
val TextMuted = Color(0xFF717171)

val TextHighEmphasis = TextPrimary
val TextMediumEmphasis = TextSecondary
val TextLowEmphasis = TextMuted

// ── Light Theme Palette ───────────────────────────────────────
val LightBackground = Color(0xFFFFFFFF)
val LightSurface = Color(0xFFF9F9F9)
val LightCard = Color(0xFFF1F1F1)
val LightBorder = Color(0xFFE5E5E5)
val LightTextPrimary = Color(0xFF0F0F0F)
val LightTextSecondary = Color(0xFF606060)

// ── Legacy Aliases ────────────────────────────────────────────
val Purple80 = EmeraldLight
val PurpleGrey80 = TextSecondary
val Pink80 = AmberGold
val Purple40 = EmeraldPrimary
val PurpleGrey40 = TextMuted
val Pink40 = AmberContainer