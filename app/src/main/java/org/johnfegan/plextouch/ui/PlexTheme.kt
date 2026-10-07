package org.johnfegan.plextouch.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val PlexBlue = Color(0xFF0082C9)
val PlexBackground = Color(0xFF0B1016)
val PlexPanel = Color(0xFF17212B)
val PlexMuted = Color(0xFFA6B3C0)
val PlexHighlight = Color(0xFF74CFFF)

@Composable
fun PlexTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PlexBlue, onPrimary = Color(0xFF000D15),
            primaryContainer = Color(0xFF073C58), onPrimaryContainer = Color(0xFFBDE8FF),
            secondary = PlexHighlight, onSecondary = PlexBackground,
            secondaryContainer = PlexPanel, onSecondaryContainer = Color.White,
            background = PlexBackground, onBackground = Color(0xFFF6F8FA),
            surface = PlexBackground, onSurface = Color(0xFFF6F8FA),
            surfaceVariant = PlexPanel, onSurfaceVariant = PlexMuted,
            outline = Color(0xFF40505F), outlineVariant = Color(0xFF26333F),
        ),
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 39.sp, letterSpacing = (-1).sp),
            headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.6).sp),
            titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
            labelSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 10.sp, lineHeight = 16.sp, letterSpacing = 1.4.sp),
        ),
        content = content,
    )
}
