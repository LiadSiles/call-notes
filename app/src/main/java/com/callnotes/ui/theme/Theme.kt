package com.callnotes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.callnotes.R

/*
 * "Quiet ink": a calm, editorial look. Warm mist background, white cards without borders
 * or shadows, a deep pine brand colour (from the launcher icon) used sparingly, brass for
 * things in progress, and soft tinted tiles to tell settings categories apart. Heebo (OFL,
 * bundled — no font downloads) for Hebrew and Latin alike. Fixed palette instead of dynamic
 * colour so the app looks the same on every phone.
 */

private val Pine10 = Color(0xFF0A2A22)
private val Pine30 = Color(0xFF1C4F43)
private val Pine80 = Color(0xFF94D2BC)
private val Pine95 = Color(0xFFE3F1EB)
private val Brass40 = Color(0xFF9C6A1C)
private val Brass95 = Color(0xFFF7EBD6)
private val Rose40 = Color(0xFFB0362E)
private val Rose95 = Color(0xFFF9E4E1)

private val Mist = Color(0xFFF4F3EF)
private val MistDim = Color(0xFFEAE8E2)
private val Ink = Color(0xFF191B1A)
private val InkSoft = Color(0xFF6A6E6B)
private val Hair = Color(0xFFE6E3DC)

private val Light = lightColorScheme(
    primary = Pine30,
    onPrimary = Color.White,
    primaryContainer = Pine95,
    onPrimaryContainer = Pine10,
    secondary = Brass40,
    onSecondary = Color.White,
    secondaryContainer = Brass95,
    onSecondaryContainer = Color(0xFF3B2604),
    tertiary = Color(0xFF4A5899),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE6E9F8),
    onTertiaryContainer = Color(0xFF16204F),
    error = Rose40,
    onError = Color.White,
    errorContainer = Rose95,
    onErrorContainer = Color(0xFF440D09),
    background = Mist,
    onBackground = Ink,
    surface = Mist,
    onSurface = Ink,
    surfaceVariant = MistDim,
    onSurfaceVariant = InkSoft,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAF9F6),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF0EEE9),
    surfaceContainerHighest = Color(0xFFE6E3DC),
    outline = Color(0xFFB8B4AB),
    outlineVariant = Hair,
    inverseSurface = Color(0xFF2B2E2C),
    inverseOnSurface = Color(0xFFF1EFEA),
)

private val Dark = darkColorScheme(
    primary = Pine80,
    onPrimary = Pine10,
    primaryContainer = Color(0xFF1D3C34),
    onPrimaryContainer = Color(0xFFC9EBDD),
    secondary = Color(0xFFE9BE76),
    onSecondary = Color(0xFF3B2604),
    secondaryContainer = Color(0xFF4A3712),
    onSecondaryContainer = Brass95,
    tertiary = Color(0xFFB7C1F2),
    onTertiary = Color(0xFF16204F),
    tertiaryContainer = Color(0xFF2B3462),
    onTertiaryContainer = Color(0xFFE6E9F8),
    error = Color(0xFFF2B6AF),
    onError = Color(0xFF440D09),
    errorContainer = Color(0xFF6E1E18),
    onErrorContainer = Rose95,
    background = Color(0xFF101312),
    onBackground = Color(0xFFE7E5E0),
    surface = Color(0xFF101312),
    onSurface = Color(0xFFE7E5E0),
    surfaceVariant = Color(0xFF232826),
    onSurfaceVariant = Color(0xFFA9AEAA),
    surfaceContainerLowest = Color(0xFF0B0D0C),
    surfaceContainerLow = Color(0xFF151918),
    surfaceContainer = Color(0xFF1A1F1D),
    surfaceContainerHigh = Color(0xFF222826),
    surfaceContainerHighest = Color(0xFF2C3230),
    outline = Color(0xFF7F8581),
    outlineVariant = Color(0xFF2C3230),
    inverseSurface = Color(0xFFE7E5E0),
    inverseOnSurface = Color(0xFF1A1F1D),
)

/** Soft tinted tiles behind settings icons, one hue per category. */
@Immutable
data class Tints(val green: Pair<Color, Color>, val indigo: Pair<Color, Color>, val brass: Pair<Color, Color>,
                 val rose: Pair<Color, Color>, val teal: Pair<Color, Color>, val slate: Pair<Color, Color>,
                 val plum: Pair<Color, Color>)

private val LightTints = Tints(
    green = Color(0xFFDDEFE6) to Color(0xFF1C4F43),
    indigo = Color(0xFFE4E7F7) to Color(0xFF3B4890),
    brass = Color(0xFFF6E8CF) to Color(0xFF8A5A12),
    rose = Color(0xFFF8E1DE) to Color(0xFF9A2E26),
    teal = Color(0xFFDCEEF0) to Color(0xFF1D5E66),
    slate = Color(0xFFE8E8E6) to Color(0xFF474B49),
    plum = Color(0xFFEFE3F1) to Color(0xFF6B3576),
)
private val DarkTints = Tints(
    green = Color(0xFF1D3C34) to Color(0xFF9AD7C1),
    indigo = Color(0xFF2B3462) to Color(0xFFC3CBF4),
    brass = Color(0xFF4A3712) to Color(0xFFEBC78A),
    rose = Color(0xFF55201B) to Color(0xFFF2B6AF),
    teal = Color(0xFF173F44) to Color(0xFF9FD5DB),
    slate = Color(0xFF2C3230) to Color(0xFFC6CBC8),
    plum = Color(0xFF41284A) to Color(0xFFE2C1E8),
)

val LocalTints = staticCompositionLocalOf { LightTints }

@OptIn(ExperimentalTextApi::class)
private fun heebo(weight: Int) = Font(
    R.font.heebo,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

@OptIn(ExperimentalTextApi::class)
val Heebo = FontFamily(heebo(300), heebo(400), heebo(500), heebo(600), heebo(700))

private fun style(size: Int, line: Int, weight: FontWeight, spacing: Double = 0.0) = TextStyle(
    fontFamily = Heebo, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = spacing.sp,
)

private val AppTypography = Typography(
    displaySmall = style(34, 42, FontWeight.SemiBold, -0.5),
    headlineLarge = style(32, 40, FontWeight.SemiBold, -0.4),
    headlineMedium = style(28, 36, FontWeight.SemiBold, -0.3),
    headlineSmall = style(22, 30, FontWeight.SemiBold, -0.2),
    titleLarge = style(20, 28, FontWeight.SemiBold),
    titleMedium = style(17, 24, FontWeight.Medium),
    titleSmall = style(15, 22, FontWeight.Medium),
    bodyLarge = style(16, 26, FontWeight.Normal),
    bodyMedium = style(15, 23, FontWeight.Normal),
    bodySmall = style(13, 19, FontWeight.Normal),
    labelLarge = style(14, 20, FontWeight.Medium),
    labelMedium = style(12, 16, FontWeight.Medium, 0.2),
    labelSmall = style(11, 16, FontWeight.Medium, 0.3),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun CallNotesTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalTints provides if (dark) DarkTints else LightTints) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
