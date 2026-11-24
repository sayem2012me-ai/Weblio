package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val WeblioDarkColorScheme = darkColorScheme(
    primary = WeblioPeriwinkle,
    onPrimary = WeblioGrayBg,
    primaryContainer = WeblioGrayElevated,
    onPrimaryContainer = WeblioPureWhite,
    secondary = WeblioCyan,
    onSecondary = WeblioGrayBg,
    secondaryContainer = WeblioGraySurfaceVariant,
    onSecondaryContainer = WeblioWhite,
    tertiary = WeblioDeepPurple,
    onTertiary = WeblioWhite,
    tertiaryContainer = WeblioIncognitoSurface,
    onTertiaryContainer = WeblioIncognitoAccent,
    background = WeblioGrayBg,
    onBackground = WeblioWhite,
    surface = WeblioGraySurface,
    onSurface = WeblioWhite,
    surfaceVariant = WeblioGraySurfaceVariant,
    onSurfaceVariant = WeblioSlateText,
    outline = WeblioCardBorder,
    outlineVariant = WeblioGrayElevated,
    error = WeblioDanger,
    onError = WeblioWhite
)

private val WeblioLightColorScheme = lightColorScheme(
    primary = WeblioElectricBlue,
    onPrimary = WeblioPureWhite,
    primaryContainer = WeblioLightElevated,
    onPrimaryContainer = WeblioElectricBlue,
    secondary = WeblioDeepPurple,
    onSecondary = WeblioPureWhite,
    secondaryContainer = WeblioLightElevated,
    onSecondaryContainer = WeblioLightText,
    tertiary = WeblioCyan,
    onTertiary = WeblioGrayBg,
    background = WeblioLightBg,
    onBackground = WeblioLightText,
    surface = WeblioLightSurface,
    onSurface = WeblioLightText,
    surfaceVariant = WeblioLightElevated,
    onSurfaceVariant = WeblioLightMuted,
    outline = WeblioLightBorder,
    outlineVariant = WeblioLightElevated,
    error = WeblioDanger,
    onError = WeblioPureWhite
)

val WeblioShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

enum class AppThemeMode(val label: String) {
    DARK("Dark"),
    LIGHT("White"),
    SYSTEM("System")
}

@Composable
fun WeblioTheme(
    themeMode: AppThemeMode = AppThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = if (isDark) WeblioDarkColorScheme else WeblioLightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity
            if (activity != null) {
                val controller = WindowCompat.getInsetsController(activity.window, view)
                controller.isAppearanceLightStatusBars = !isDark
                controller.isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = WeblioTypography,
        shapes = WeblioShapes,
        content = content
    )
}
