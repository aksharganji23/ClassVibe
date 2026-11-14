package com.classvibe.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.classvibe.app.data.ThemeMode
import kotlin.math.abs

// ---------------- Brand palette ----------------
val Brand = Color(0xFF0055A4)       // royal blue (both themes)
val BrandLight = Color(0xFF5AA2F0)  // readable blue on black
val Orange = Color(0xFFFF8A00)      // light-theme accent
val OrangeBright = Color(0xFFFFA733) // dark-theme accent

private val LightColors: ColorScheme = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F0FA),
    onPrimaryContainer = Color(0xFF002A55),
    secondary = Orange,
    onSecondary = Color(0xFF1A1C21),
    secondaryContainer = Color(0xFFFFE8CC),
    onSecondaryContainer = Color(0xFF4A2800),
    tertiary = Color(0xFF00897B),
    background = Color.White,
    onBackground = Color(0xFF1A1C21),
    surface = Color.White,
    onSurface = Color(0xFF1A1C21),
    surfaceVariant = Color(0xFFEDF2F8),
    onSurfaceVariant = Color(0xFF5F6670),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F9FC),
    surfaceContainer = Color(0xFFF4F7FB),
    surfaceContainerHigh = Color(0xFFEDF2F8),
    surfaceContainerHighest = Color(0xFFE6EDF6),
    outline = Color(0xFFC9D1DC),
    outlineVariant = Color(0xFFE1E6EE),
    error = Color(0xFFC62828),
    onError = Color.White,
    errorContainer = Color(0xFFFDECEC),
    onErrorContainer = Color(0xFF5F0B0B)
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = BrandLight,
    onPrimary = Color(0xFF00213F),
    primaryContainer = Brand,
    onPrimaryContainer = Color.White,
    secondary = OrangeBright,
    onSecondary = Color(0xFF2A1700),
    secondaryContainer = Color(0xFF4A2E00),
    onSecondaryContainer = Color(0xFFFFDDB3),
    tertiary = Color(0xFF4DD0E1),
    background = Color.Black,
    onBackground = Color(0xFFF2F4F7),
    surface = Color.Black,
    onSurface = Color(0xFFF2F4F7),
    surfaceVariant = Color(0xFF1F2329),
    onSurfaceVariant = Color(0xFFA0A7B2),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0D10),
    surfaceContainer = Color(0xFF121418),
    surfaceContainerHigh = Color(0xFF181B20),
    surfaceContainerHighest = Color(0xFF1F2329),
    outline = Color(0xFF3A4049),
    outlineVariant = Color(0xFF262A31),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2B0010),
    errorContainer = Color(0xFF3B1414),
    onErrorContainer = Color(0xFFFFDAD6)
)

/** Extra colours that Material's scheme doesn't have. */
data class CvColors(
    val isDark: Boolean,
    val brand: Color,
    val brandText: Color,
    val accent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val coursePalette: List<Color>
)

private val LightCv = CvColors(
    isDark = false,
    brand = Brand,
    brandText = Brand,
    accent = Orange,
    success = Color(0xFF2E7D32),
    warning = Color(0xFFE08600),
    danger = Color(0xFFC62828),
    coursePalette = listOf(
        Color(0xFF0055A4), Color(0xFFD35400), Color(0xFF00897B), Color(0xFF8E24AA), Color(0xFFC2185B),
        Color(0xFF2E7D32), Color(0xFF5D4037), Color(0xFF00838F), Color(0xFF3949AB)
    )
)

private val DarkCv = CvColors(
    isDark = true,
    brand = Brand,
    brandText = BrandLight,
    accent = OrangeBright,
    success = Color(0xFF81C784),
    warning = Color(0xFFFFD54F),
    danger = Color(0xFFFF6B6B),
    coursePalette = listOf(
        Color(0xFF5AA2F0), Color(0xFFFFA733), Color(0xFF4DD0E1), Color(0xFFCE93D8), Color(0xFFF48FB1),
        Color(0xFF81C784), Color(0xFFFFD54F), Color(0xFF80CBC4), Color(0xFF9FA8DA)
    )
)

val LocalCvColors = staticCompositionLocalOf { LightCv }

object Cv {
    val colors: CvColors
        @Composable get() = LocalCvColors.current
}

@Composable
fun courseColor(course: String): Color {
    val palette = LocalCvColors.current.coursePalette
    return palette[abs(course.trim().lowercase().hashCode()) % palette.size]
}

@Composable
fun classTypeColor(type: String): Color {
    val cv = LocalCvColors.current
    return when (type.trim().lowercase()) {
        "lab", "laboratory", "practical" -> cv.success
        "tutorial", "tut" -> cv.accent
        else -> cv.brandText
    }
}

private val ClassVibeType = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
    )
}

@Composable
fun isDarkFor(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    else -> false // LIGHT and first launch default to light
}

@Composable
fun ClassVibeTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = isDarkFor(mode)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(LocalCvColors provides if (dark) DarkCv else LightCv) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = ClassVibeType, content = content)
    }
}
