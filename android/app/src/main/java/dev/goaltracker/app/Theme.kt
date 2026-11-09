package dev.goaltracker.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The few colors the app uses, taken from Apple's system colors. It follows the phone's light or dark setting. */
@Immutable
class Palette(
    val background: Color,
    val label: Color,
    val secondary: Color,
    val separator: Color,
    val ring: Color,
    val blue: Color,
    val danger: Color,
    val grouped: Color,
    val card: Color,
)

private val Light = Palette(
    background = Color.White,
    label = Color.Black,
    secondary = Color(0xFF6C6C70),
    separator = Color(0xFFC6C6C8),
    ring = Color(0xFFC7C7CC),
    blue = Color(0xFF007AFF),
    danger = Color(0xFFD70015),
    grouped = Color(0xFFF2F2F7),
    card = Color.White,
)

private val Dark = Palette(
    background = Color.Black,
    label = Color.White,
    secondary = Color(0xFF98989D),
    separator = Color(0xFF38383A),
    ring = Color(0xFF636366),
    blue = Color(0xFF0A84FF),
    danger = Color(0xFFFF6961),
    grouped = Color.Black,
    card = Color(0xFF1C1C1E),
)

val LocalPalette = staticCompositionLocalOf { Light }

@Composable
fun JarvestTheme(content: @Composable () -> Unit) {
    val palette = if (isSystemInDarkTheme()) Dark else Light
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = palette.blue, background = palette.background, surface = palette.card,
            onSurface = palette.label, onBackground = palette.label, onSurfaceVariant = palette.secondary,
        )
    } else {
        lightColorScheme(
            primary = palette.blue, background = palette.background, surface = palette.card,
            onSurface = palette.label, onBackground = palette.label, onSurfaceVariant = palette.secondary,
        )
    }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
