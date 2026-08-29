package com.anonymous.imgpdf.ui.theme

import android.app.Activity
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = PaperlyCoralBright,
    onPrimary = Color(0xFF3D0904),
    primaryContainer = Color(0xFF6C2B22),
    onPrimaryContainer = Color(0xFFFFDAD3),
    secondary = Color(0xFFC7B8FF),
    onSecondary = Color(0xFF24135F),
    secondaryContainer = Color(0xFF493A8D),
    onSecondaryContainer = Color(0xFFE8DEFF),
    tertiary = Color(0xFF61D7C5),
    onTertiary = Color(0xFF003731),
    tertiaryContainer = Color(0xFF005047),
    onTertiaryContainer = Color(0xFF80F8E5),
    background = PaperlyDarkCanvas,
    onBackground = PaperlyDarkInk,
    surface = PaperlyDarkSurface,
    onSurface = PaperlyDarkInk,
    surfaceVariant = PaperlyDarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFD0C8D0),
    outline = PaperlyDarkOutline,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val LightColorScheme = lightColorScheme(
    primary = PaperlyCoral,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDED8),
    onPrimaryContainer = Color(0xFF3D0904),
    secondary = PaperlyViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E2FF),
    onSecondaryContainer = Color(0xFF21105C),
    tertiary = PaperlyTeal,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC7F2E9),
    onTertiaryContainer = Color(0xFF00201C),
    background = PaperlyCanvas,
    onBackground = PaperlyInk,
    surface = PaperlySurface,
    onSurface = PaperlyInk,
    surfaceVariant = PaperlySurfaceVariant,
    onSurfaceVariant = Color(0xFF716A73),
    outline = PaperlyOutline,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

@Composable
fun PDFToolkitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // Determine the actual dark theme state based on AppCompatDelegate mode
    val actualDarkTheme = when (AppCompatDelegate.getDefaultNightMode()) {
        AppCompatDelegate.MODE_NIGHT_YES -> true
        AppCompatDelegate.MODE_NIGHT_NO -> false
        else -> darkTheme // MODE_NIGHT_FOLLOW_SYSTEM or unspecified
    }

    // Keep the product palette stable across phones. System dynamic colors
    // are still available to callers that explicitly opt in.
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (actualDarkTheme) {
                androidx.compose.material3.dynamicDarkColorScheme(context)
            } else {
                androidx.compose.material3.dynamicLightColorScheme(context)
            }
        }
        actualDarkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !actualDarkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !actualDarkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}