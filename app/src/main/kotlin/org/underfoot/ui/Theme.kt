package org.underfoot.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Lime = Color(0xFFC6F55A)

private val DarkScheme = darkColorScheme(
    primary = Lime, onPrimary = Color(0xFF11140A),
    primaryContainer = Color(0xFF2E3D0E), onPrimaryContainer = Lime,
    secondaryContainer = Color(0xFF26331A), onSecondaryContainer = Lime,
    background = Color(0xFF0E1013), onBackground = Color(0xFFEDEFF2),
    surface = Color(0xFF0E1013), onSurface = Color(0xFFEDEFF2),
    surfaceVariant = Color(0xFF1B1F25), onSurfaceVariant = Color(0xFFA9B0BA),
    surfaceContainer = Color(0xFF14171C), outline = Color(0xFF3A414A),
    error = Color(0xFFFF5A5F), onError = Color.White, errorContainer = Color(0xFF3A1416), onErrorContainer = Color(0xFFFFB3B5),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF3F6B00), onPrimary = Color.White,
    primaryContainer = Color(0xFFD7F59B), onPrimaryContainer = Color(0xFF142400),
    secondaryContainer = Color(0xFFE2EBCF), onSecondaryContainer = Color(0xFF1A2A00),
    background = Color(0xFFF7F8F4), onBackground = Color(0xFF191C16),
    surface = Color(0xFFF7F8F4), onSurface = Color(0xFF191C16),
    surfaceVariant = Color(0xFFE6EADB), onSurfaceVariant = Color(0xFF454B3E),
    surfaceContainer = Color(0xFFEDF0E4), outline = Color(0xFF757B6C),
    error = Color(0xFFBA1A1A), onError = Color.White, errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
)

/** Dark with a lime accent (the default), the light variant, or whichever the system uses. */
@Composable
fun UnderfootTheme(mode: String = "dark", content: @Composable () -> Unit) {
    val dark = when (mode) { "light" -> false; "system" -> isSystemInDarkTheme(); else -> true }
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
}
