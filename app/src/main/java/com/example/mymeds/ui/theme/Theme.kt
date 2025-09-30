package com.example.mymeds.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = LightBluePrimary,      // #9FB3DF (Used for buttons, primary text, active elements)
    secondary = SkyBlueSecondary,    // #9EC6F3 (Used for secondary buttons, floating action buttons)
    tertiary = TealTertiary,         // #BDDDE4 (Used for accents, complementary elements)
    background = White,              // #FFFFFF (Main screen background)
    surface = CreamBackground,       // #FFF1D5 (Used for cards, text field backgrounds, dialogs)
    onPrimary = White,               // Color of content (text, icons) on top of primary
    onSecondary = White,
    onTertiary = DarkGrey,
    onBackground = Black,            // Color of content on top of background
    onSurface = DarkGrey             // Color of content on top of surface
)

// --- Dark Color Scheme (Basic Inversion) ---
// Note: You should fine-tune these colors for actual dark mode use.
private val DarkColorScheme = darkColorScheme(
    primary = LightBluePrimary,
    secondary = SkyBlueSecondary,
    tertiary = TealTertiary,
    background = Black,
    surface = DarkGrey,
    onPrimary = Black,
    onSecondary = Black,
    onTertiary = White,
    onBackground = White,
    onSurface = White
)

@Composable
fun MyMedsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography, // Uses the Typography object defined in Type.kt
        content = content
    )
}