package dev.goaltracker.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

enum class Accent(val label: String) { BLUE("Blue"), GREEN("Green"), PURPLE("Purple") }

/** Whether the app is currently drawn dark, which can differ from the phone's setting. */
val LocalDarkTheme = compositionLocalOf { false }

private val LightColors = lightColorScheme(
    primary = Color(0xFF256ABF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE2FB),
    onPrimaryContainer = Color(0xFF0D366B),
    secondary = Color(0xFF184F95),
    secondaryContainer = Color(0xFFCDE2FB),
    onSecondaryContainer = Color(0xFF0D366B),
    background = Color(0xFFFBFBFA),
    surface = Color(0xFFFBFBFA),
    onSurface = Color(0xFF1B1B1A),
    surfaceVariant = Color(0xFFECECEA),
    onSurfaceVariant = Color(0xFF52524F),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F5F3),
    surfaceContainer = Color(0xFFF1F1EF),
    surfaceContainerHigh = Color(0xFFECECEA),
    surfaceContainerHighest = Color(0xFFE7E7E4),
    outline = Color(0xFF7A7A76),
    outlineVariant = Color(0xFFCBCBC7),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9EC5F4),
    onPrimary = Color(0xFF0D366B),
    primaryContainer = Color(0xFF184F95),
    onPrimaryContainer = Color(0xFFCDE2FB),
    secondary = Color(0xFF86B6EF),
    secondaryContainer = Color(0xFF184F95),
    onSecondaryContainer = Color(0xFFCDE2FB),
    background = Color(0xFF191918),
    surface = Color(0xFF191918),
    onSurface = Color(0xFFE6E6E3),
    surfaceVariant = Color(0xFF383835),
    onSurfaceVariant = Color(0xFFBDBDB8),
    surfaceContainerLowest = Color(0xFF131312),
    surfaceContainerLow = Color(0xFF1F1F1E),
    surfaceContainer = Color(0xFF242423),
    surfaceContainerHigh = Color(0xFF2B2B29),
    surfaceContainerHighest = Color(0xFF32322F),
    outline = Color(0xFF8D8D88),
    outlineVariant = Color(0xFF4A4A47),
)

private fun lightFor(accent: Accent) = when (accent) {
    Accent.BLUE -> LightColors
    Accent.GREEN -> LightColors.copy(
        primary = Color(0xFF2B6E43),
        primaryContainer = Color(0xFFCFEBD8),
        onPrimaryContainer = Color(0xFF0F3A20),
        secondary = Color(0xFF1F5233),
        secondaryContainer = Color(0xFFCFEBD8),
        onSecondaryContainer = Color(0xFF0F3A20),
    )
    Accent.PURPLE -> LightColors.copy(
        primary = Color(0xFF6046B0),
        primaryContainer = Color(0xFFE2DAF7),
        onPrimaryContainer = Color(0xFF2D1A66),
        secondary = Color(0xFF47318A),
        secondaryContainer = Color(0xFFE2DAF7),
        onSecondaryContainer = Color(0xFF2D1A66),
    )
}

private fun darkFor(accent: Accent) = when (accent) {
    Accent.BLUE -> DarkColors
    Accent.GREEN -> DarkColors.copy(
        primary = Color(0xFF9AD3AF),
        onPrimary = Color(0xFF0F3A20),
        primaryContainer = Color(0xFF1F5233),
        onPrimaryContainer = Color(0xFFCFEBD8),
        secondary = Color(0xFF84C29B),
        secondaryContainer = Color(0xFF1F5233),
        onSecondaryContainer = Color(0xFFCFEBD8),
    )
    Accent.PURPLE -> DarkColors.copy(
        primary = Color(0xFFC3B3F0),
        onPrimary = Color(0xFF2D1A66),
        primaryContainer = Color(0xFF47318A),
        onPrimaryContainer = Color(0xFFE2DAF7),
        secondary = Color(0xFFB0A0E4),
        secondaryContainer = Color(0xFF47318A),
        onSecondaryContainer = Color(0xFFE2DAF7),
    )
}

/** Fixed palettes, because the heatmap's steps were chosen against these surfaces. */
@Composable
fun GoalTrackerTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    accent: Accent = Accent.BLUE,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // Status bar icons must contrast with the chosen theme, not the phone's.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(
            colorScheme = if (dark) darkFor(accent) else lightFor(accent),
            content = content,
        )
    }
}

/** One-hue steps for 1, 2, 3, and 4+ logs. Each mode uses its own validated steps. */
private val HeatLight = listOf(Color(0xFF86B6EF), Color(0xFF3987E5), Color(0xFF1C5CAB), Color(0xFF0D366B))
private val HeatDark = listOf(Color(0xFF1C5CAB), Color(0xFF2A78D6), Color(0xFF5598E7), Color(0xFF9EC5F4))

@Composable
fun heatColor(count: Int): Color {
    val dark = LocalDarkTheme.current
    if (count <= 0) return if (dark) Color(0xFF383835) else Color(0xFFECECEA)
    return (if (dark) HeatDark else HeatLight)[minOf(count, 4) - 1]
}
