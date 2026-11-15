package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LenscribeDarkColorScheme = darkColorScheme(
    primary = EmeraldMint,
    onPrimary = ObsidianBg,
    primaryContainer = EmeraldContainer,
    onPrimaryContainer = EmeraldMint,
    secondary = CyanAccent,
    onSecondary = ObsidianBg,
    secondaryContainer = CyanContainer,
    onSecondaryContainer = CyanAccent,
    tertiary = VerifyAmber,
    onTertiary = ObsidianBg,
    background = ObsidianBg,
    onBackground = InkLight,
    surface = ObsidianSurface,
    onSurface = InkLight,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = InkMuted,
    outline = ObsidianBorder,
    outlineVariant = Color(0xFF1B222C),
    error = ErrorRed,
    onError = ObsidianBg,
    errorContainer = ErrorContainerDark,
    onErrorContainer = ErrorRed
)

private val LenscribeLightColorScheme = lightColorScheme(
    primary = EmeraldDeep,
    onPrimary = Color.White,
    primaryContainer = PaperEmeraldContainer,
    onPrimaryContainer = Color(0xFF064E3B),
    secondary = Color(0xFF0284C7),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF0C4A6E),
    tertiary = Color(0xFFD97706),
    onTertiary = Color.White,
    background = PaperBg,
    onBackground = PaperInk,
    surface = PaperSurface,
    onSurface = PaperInk,
    surfaceVariant = PaperSurfaceVariant,
    onSurfaceVariant = PaperInkMuted,
    outline = PaperBorder,
    outlineVariant = Color(0xFFE5DFD3),
    error = Color(0xFFDC2626),
    onError = Color.White
)

private val LenscribeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun LenscribeTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) LenscribeDarkColorScheme else LenscribeLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = LenscribeTypography,
        shapes = LenscribeShapes,
        content = content
    )
}
