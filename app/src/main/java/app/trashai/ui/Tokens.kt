package app.trashai.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens — keep colors / sizes / spacing unified across the app so
 * every screen reads as one product, not a patchwork.
 */
object Tokens {
    // Brand
    val PrimaryGreen = Color(0xFF34A853) // Brighter, modern green
    val PrimaryGreenSoft = Color(0xFFF1F8E9) // Lighter soft background
    val PrimaryGreenTint = Color(0x1A34A853) // ~10% alpha of PrimaryGreen
    val Accent = Color(0xFF81C784)

    // Surfaces
    val Background = Color(0xFFF8F9FA)
    val Surface = Color.White
    val SurfaceMuted = Color(0xFFF5F7F4)
    val Divider = Color(0xFFE6E6E6)

    // Text
    val TextPrimary = Color(0xFF1B1B1B)
    val TextSecondary = Color(0xFF6B6B6B)
    val TextOnDark = Color.White

    // Status
    val Warning = Color(0xFFFFF3E0)
    val WarningText = Color(0xFFB26A00)
    val Danger = Color(0xFFD32F2F)

    // Camera HUD
    val Scrim = Color(0x66000000)
    val ScrimStrong = Color(0xCC000000)
    val NeonGreen = Color(0xFF7CFF6B)

    // Type sizes
    val TitleSize = 22.sp
    val SubtitleSize = 13.sp
    val SectionSize = 14.sp
    val BodySize = 14.sp
    val CaptionSize = 12.sp
    val TagSize = 11.sp

    // Spacing rhythm
    val Sp4 = 4.dp
    val Sp6 = 6.dp
    val Sp8 = 8.dp
    val Sp12 = 12.dp
    val Sp16 = 16.dp
    val Sp24 = 24.dp
    val Radius12 = 12.dp
    val Radius16 = 16.dp
    val Radius24 = 24.dp
}
