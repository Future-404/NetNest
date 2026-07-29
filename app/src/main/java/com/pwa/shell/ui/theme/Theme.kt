package com.pwa.shell.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryNeonDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryNeonDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryNeonDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    background = GlassBackgroundDark,
    onBackground = OnBackgroundDark,
    surface = GlassSurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = GlassSurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    surfaceContainer = GlassSurfaceContainerDark,
    surfaceContainerHigh = GlassSurfaceHighDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryVividLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryVividLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryVividLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    background = GlassBackgroundLight,
    onBackground = OnBackgroundLight,
    surface = GlassSurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = GlassSurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    surfaceContainer = GlassSurfaceContainerLight,
    surfaceContainerHigh = GlassSurfaceHighLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight
)

@Composable
fun NetNestTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Set default to false to prioritize NetNest's custom Glassmorphism theme
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Configure status bar and navigation bar to be fully transparent for Glassmorphism
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            
            // Set edge-to-edge window configuration
            WindowCompat.setDecorFitsSystemWindows(window, false)
            
            // Adjust system bar icons brightness dynamically
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
