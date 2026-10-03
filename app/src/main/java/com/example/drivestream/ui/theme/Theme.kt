package com.example.drivestream.ui.theme

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.drivestream.AccentColor
import com.example.drivestream.AppTheme

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    background = DarkBackground,
    surface = DarkSurface
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

// ── Accent palettes (dark-friendly primary + container pairs) ──────────────
private object Accents {
    // BLUE
    val bluePrimary    = Color(0xFF2196F3)
    val blueContainer  = Color(0xFF1565C0)
    // GREEN
    val greenPrimary   = Color(0xFF4CAF50)
    val greenContainer = Color(0xFF2E7D32)
    // GREY (Blue-Grey)
    val greyPrimary    = Color(0xFF78909C)
    val greyContainer  = Color(0xFF455A64)
    // PURPLE
    val purplePrimary  = Color(0xFF7E57C2)
    val purpleContainer= Color(0xFF4527A0)
}

private fun accentPrimary(accent: AccentColor) = when (accent) {
    AccentColor.BLUE   -> Accents.bluePrimary
    AccentColor.GREEN  -> Accents.greenPrimary
    AccentColor.GREY   -> Accents.greyPrimary
    AccentColor.PURPLE -> Accents.purplePrimary
}

private fun accentContainer(accent: AccentColor) = when (accent) {
    AccentColor.BLUE   -> Accents.blueContainer
    AccentColor.GREEN  -> Accents.greenContainer
    AccentColor.GREY   -> Accents.greyContainer
    AccentColor.PURPLE -> Accents.purpleContainer
}

@Composable
fun DriveStreamTheme(
    appTheme: AppTheme = AppTheme.SYSTEM,
    accentColor: AccentColor = AccentColor.BLUE,
    dynamicColor: Boolean = false,     // disabled — user picks their own accent
    content: @Composable () -> Unit
) {
    val darkTheme = when (appTheme) {
        AppTheme.LIGHT  -> false
        AppTheme.DARK, AppTheme.AMOLED -> true
        AppTheme.SYSTEM -> isSystemInDarkTheme()
    }

    // 1. Pick base scheme
    val baseScheme = when {
        appTheme == AppTheme.AMOLED -> DarkColorScheme.copy(
            background = Color.Black,
            surface    = Color.Black
        )
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else      -> LightColorScheme
    }

    // 2. Override primary + primaryContainer with the chosen accent
    val chosenPrimary = accentPrimary(accentColor)
    val onPrimaryColor = if (accentColor == AccentColor.GREEN) Color.Black else Color.White
    val colorScheme = baseScheme.copy(
        primary          = chosenPrimary,
        primaryContainer = accentContainer(accentColor),
        onPrimary        = onPrimaryColor,
        onPrimaryContainer = onPrimaryColor
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor      = Color.Transparent.toArgb()
            window.navigationBarColor  = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars     = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,
        content     = content
    )
}
