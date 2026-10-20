package com.callnotes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * "Ink on paper": a deep green brand colour (from the launcher icon), warm paper
 * neutrals, one amber accent for things in progress. Fixed palette instead of dynamic
 * colour so the app looks the same on every phone.
 */

private val Green10 = Color(0xFF0B2A21)
private val Green30 = Color(0xFF1F5C4A)
private val Green40 = Color(0xFF2A7560)
private val Green80 = Color(0xFF8FD3BA)
private val Green90 = Color(0xFFCDEFE1)
private val Amber40 = Color(0xFFA86B12)
private val Amber90 = Color(0xFFFFE2B8)
private val Red40 = Color(0xFFB3261E)
private val Red90 = Color(0xFFF9DEDC)

private val Paper = Color(0xFFF7F5F0)
private val PaperDim = Color(0xFFEFECE5)
private val Ink = Color(0xFF1C1B19)
private val InkSoft = Color(0xFF5F5C55)
private val Line = Color(0xFFE2DED5)

private val Light = lightColorScheme(
    primary = Green30,
    onPrimary = Color.White,
    primaryContainer = Green90,
    onPrimaryContainer = Green10,
    secondary = Amber40,
    onSecondary = Color.White,
    secondaryContainer = Amber90,
    onSecondaryContainer = Color(0xFF3A2400),
    error = Red40,
    errorContainer = Red90,
    onErrorContainer = Color(0xFF410E0B),
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperDim,
    onSurfaceVariant = InkSoft,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFCFBF8),
    surfaceContainer = Color.White,
    surfaceContainerHigh = PaperDim,
    surfaceContainerHighest = Color(0xFFE8E4DC),
    outline = Color(0xFFB9B4AA),
    outlineVariant = Line,
)

private val Dark = darkColorScheme(
    primary = Green80,
    onPrimary = Green10,
    primaryContainer = Green40,
    onPrimaryContainer = Green90,
    secondary = Color(0xFFF2BD6B),
    onSecondary = Color(0xFF3A2400),
    secondaryContainer = Color(0xFF5A3D0A),
    onSecondaryContainer = Amber90,
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Red90,
    background = Color(0xFF111312),
    onBackground = Color(0xFFE6E3DD),
    surface = Color(0xFF111312),
    onSurface = Color(0xFFE6E3DD),
    surfaceVariant = Color(0xFF242725),
    onSurfaceVariant = Color(0xFFB7B3AB),
    surfaceContainerLowest = Color(0xFF0C0E0D),
    surfaceContainerLow = Color(0xFF171A18),
    surfaceContainer = Color(0xFF1B1E1C),
    surfaceContainerHigh = Color(0xFF242725),
    surfaceContainerHighest = Color(0xFF2E322F),
    outline = Color(0xFF8A867E),
    outlineVariant = Color(0xFF353935),
)

private val Base = Typography()

private val AppTypography = Typography(
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.copy(lineHeight = 26.sp),
    bodyMedium = Base.bodyMedium.copy(lineHeight = 22.sp),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = Base.labelMedium.copy(fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun CallNotesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
