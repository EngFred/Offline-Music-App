package com.engfred.musicplayer.core.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class AppThemeType {
    CLASSIC_BLUE,
    SUNSET_GROOVE,
    CLASSIC_LIGHT,
    NEON_DARK,
    DARK,
}

private val LightColorScheme = lightColorScheme(
    primary = FrostPrimary,
    onPrimary = FrostOnPrimary,
    primaryContainer = FrostPrimaryContainer,
    onPrimaryContainer = FrostOnPrimaryContainer,
    secondary = FrostSecondary,
    onSecondary = FrostOnSecondary,
    secondaryContainer = FrostSecondaryContainer,
    onSecondaryContainer = FrostOnSecondaryContainer,
    tertiary = FrostTertiary,
    onTertiary = FrostOnTertiary,
    tertiaryContainer = FrostTertiaryContainer,
    onTertiaryContainer = FrostOnTertiaryContainer,
    background = FrostBackground,
    onBackground = FrostOnBackground,
    surface = FrostSurface,
    onSurface = FrostOnSurface,
    surfaceVariant = FrostSurfaceVariant,
    onSurfaceVariant = FrostOnSurfaceVariant,
    error = FrostError,
    onError = FrostOnError,
    outline = FrostOutline,
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    error = DarkError,
    onError = DarkOnError,
    outline = DarkOutline,
)

private val DeepBlueColorScheme = darkColorScheme(
    primary = DeepBluePrimary,
    onPrimary = DeepBlueOnPrimary,
    primaryContainer = DeepBluePrimaryContainer,
    onPrimaryContainer = DeepBlueOnPrimaryContainer,
    secondary = DeepBlueSecondary,
    onSecondary = DeepBlueOnSecondary,
    secondaryContainer = DeepBlueSecondaryContainer,
    onSecondaryContainer = DeepBlueOnSecondaryContainer,
    tertiary = DeepBlueTertiary,
    onTertiary = DeepBlueOnTertiary,
    tertiaryContainer = DeepBlueTertiaryContainer,
    onTertiaryContainer = DeepBlueOnTertiaryContainer,
    background = DeepBlueBackground,
    onBackground = DeepBlueOnBackground,
    surface = DeepBlueSurface,
    onSurface = DeepBlueOnSurface,
    surfaceVariant = DeepBlueSurfaceVariant,
    onSurfaceVariant = DeepBlueOnSurfaceVariant,
    error = DeepBlueError,
    onError = DeepBlueOnError,
    outline = DeepBlueOutline,
)

private val NeonPulseColorScheme = darkColorScheme(
    primary = NeonPrimary,
    onPrimary = NeonOnPrimary,
    primaryContainer = NeonPrimaryContainer,
    onPrimaryContainer = NeonOnPrimaryContainer,
    secondary = NeonSecondary,
    onSecondary = NeonOnSecondary,
    secondaryContainer = NeonSecondaryContainer,
    onSecondaryContainer = NeonOnSecondaryContainer,
    tertiary = NeonTertiary,
    onTertiary = NeonOnTertiary,
    tertiaryContainer = NeonTertiaryContainer,
    onTertiaryContainer = NeonOnTertiaryContainer,
    background = NeonBackground,
    onBackground = NeonOnBackground,
    surface = NeonSurface,
    onSurface = NeonOnSurface,
    surfaceVariant = NeonSurfaceVariant,
    onSurfaceVariant = NeonOnSurfaceVariant,
    error = NeonError,
    onError = NeonOnError,
    outline = NeonOutline,
)

private val SunsetGrooveColorScheme = darkColorScheme(
    primary = SunsetPrimary,
    onPrimary = SunsetOnPrimary,
    primaryContainer = SunsetPrimaryContainer,
    onPrimaryContainer = SunsetOnPrimaryContainer,
    secondary = SunsetSecondary,
    onSecondary = SunsetOnSecondary,
    secondaryContainer = SunsetSecondaryContainer,
    onSecondaryContainer = SunsetOnSecondaryContainer,
    tertiary = SunsetTertiary,
    onTertiary = SunsetOnTertiary,
    tertiaryContainer = SunsetTertiaryContainer,
    onTertiaryContainer = SunsetOnTertiaryContainer,
    background = SunsetBackground,
    onBackground = SunsetOnBackground,
    surface = SunsetSurface,
    onSurface = SunsetOnSurface,
    surfaceVariant = SunsetSurfaceVariant,
    onSurfaceVariant = SunsetOnSurfaceVariant,
    error = SunsetError,
    onError = SunsetOnError,
    outline = SunsetOutline,
)

// --- Apply Theme ---

@Composable
fun MusicPlayerAppTheme(
    selectedTheme: AppThemeType,
    content: @Composable () -> Unit
) {
    val colorScheme = when (selectedTheme) {
        AppThemeType.CLASSIC_LIGHT -> LightColorScheme
        AppThemeType.DARK -> DarkColorScheme
        AppThemeType.CLASSIC_BLUE -> DeepBlueColorScheme
        AppThemeType.NEON_DARK -> NeonPulseColorScheme
        AppThemeType.SUNSET_GROOVE -> SunsetGrooveColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()

            val isLightTheme = selectedTheme == AppThemeType.CLASSIC_LIGHT

            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = isLightTheme
                isAppearanceLightNavigationBars = isLightTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = Shapes,
        content = content
    )
}