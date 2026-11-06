package dev.goaltracker.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark"), BLACK("Black (OLED)") }

enum class Accent(val label: String) {
    BLUE("Blue"), GREEN("Green"), PURPLE("Purple"),
    ROSE("Rose"), TEAL("Teal"), AMBER("Amber"), GRAPHITE("Graphite"),
}

/** Whether the app is currently drawn dark, which can differ from the phone's setting. */
val LocalDarkTheme = compositionLocalOf { false }

internal val LightColors = lightColorScheme(
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

internal val DarkColors = darkColorScheme(
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
    Accent.ROSE -> LightColors.copy(
        primary = Color(0xFFB3264F),
        primaryContainer = Color(0xFFFAD4DD),
        onPrimaryContainer = Color(0xFF5E0F27),
        secondary = Color(0xFF8A1D3E),
        secondaryContainer = Color(0xFFFAD4DD),
        onSecondaryContainer = Color(0xFF5E0F27),
    )
    Accent.TEAL -> LightColors.copy(
        primary = Color(0xFF0F6B6B),
        primaryContainer = Color(0xFFC4EBEA),
        onPrimaryContainer = Color(0xFF053B3B),
        secondary = Color(0xFF0B5050),
        secondaryContainer = Color(0xFFC4EBEA),
        onSecondaryContainer = Color(0xFF053B3B),
    )
    Accent.AMBER -> LightColors.copy(
        primary = Color(0xFF8A5100),
        primaryContainer = Color(0xFFFFDDB0),
        onPrimaryContainer = Color(0xFF4A2A00),
        secondary = Color(0xFF6B3F00),
        secondaryContainer = Color(0xFFFFDDB0),
        onSecondaryContainer = Color(0xFF4A2A00),
    )
    Accent.GRAPHITE -> LightColors.copy(
        primary = Color(0xFF45494F),
        primaryContainer = Color(0xFFDFE2E8),
        onPrimaryContainer = Color(0xFF1C1F24),
        secondary = Color(0xFF33373C),
        secondaryContainer = Color(0xFFDFE2E8),
        onSecondaryContainer = Color(0xFF1C1F24),
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
    Accent.ROSE -> DarkColors.copy(
        primary = Color(0xFFF2A3B8),
        onPrimary = Color(0xFF5E0F27),
        primaryContainer = Color(0xFF8A1D3E),
        onPrimaryContainer = Color(0xFFFAD4DD),
        secondary = Color(0xFFE88DA5),
        secondaryContainer = Color(0xFF8A1D3E),
        onSecondaryContainer = Color(0xFFFAD4DD),
    )
    Accent.TEAL -> DarkColors.copy(
        primary = Color(0xFF86D3D1),
        onPrimary = Color(0xFF053B3B),
        primaryContainer = Color(0xFF0B5050),
        onPrimaryContainer = Color(0xFFC4EBEA),
        secondary = Color(0xFF6FC0BE),
        secondaryContainer = Color(0xFF0B5050),
        onSecondaryContainer = Color(0xFFC4EBEA),
    )
    Accent.AMBER -> DarkColors.copy(
        primary = Color(0xFFFFB955),
        onPrimary = Color(0xFF4A2A00),
        primaryContainer = Color(0xFF6B3F00),
        onPrimaryContainer = Color(0xFFFFDDB0),
        secondary = Color(0xFFF0B060),
        secondaryContainer = Color(0xFF6B3F00),
        onSecondaryContainer = Color(0xFFFFDDB0),
    )
    Accent.GRAPHITE -> DarkColors.copy(
        primary = Color(0xFFC4C8CF),
        onPrimary = Color(0xFF1C1F24),
        primaryContainer = Color(0xFF3A3E44),
        onPrimaryContainer = Color(0xFFDFE2E8),
        secondary = Color(0xFFB0B5BC),
        secondaryContainer = Color(0xFF3A3E44),
        onSecondaryContainer = Color(0xFFDFE2E8),
    )
}

/** True black surfaces for OLED screens: dark colors with the backgrounds pushed to black. */
private fun blackFor(accent: Accent) = darkFor(accent).copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF2A2A28),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0B),
    surfaceContainer = Color(0xFF121211),
    surfaceContainerHigh = Color(0xFF1A1A19),
    surfaceContainerHighest = Color(0xFF222221),
    outlineVariant = Color(0xFF3A3A38),
)

/** The scheme for a mode and accent. Internal so the contrast test can check every pair. */
internal fun colorSchemeFor(mode: ThemeMode, dark: Boolean, accent: Accent) = when {
    mode == ThemeMode.BLACK -> blackFor(accent)
    dark -> darkFor(accent)
    else -> lightFor(accent)
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
        ThemeMode.DARK, ThemeMode.BLACK -> true
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
    val context = LocalContext.current
    val motion = Feel.motion && !Feel.reducedMotion(context)
    CompositionLocalProvider(LocalDarkTheme provides dark, LocalMotion provides motion) {
        MaterialTheme(
            colorScheme = colorSchemeFor(mode, dark, accent),
            shapes = SoftShapes,
            content = content,
        )
    }
}

/** Rounder corners everywhere: cards, sheets, dialogs, chips and buttons pick these up. */
internal val SoftShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** Card colors: a soft tinted surface with no shadow, so lists look calm. */
@Composable
fun softCardColors(): CardColors = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
)

/**
 * The app's card. It has big rounded corners, a faint outline instead of a shadow, and works
 * like `Card` whether or not it is clickable.
 */
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    colors: CardColors = softCardColors(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors, border = border, content = content)
    } else {
        Card(modifier = modifier, shape = shape, colors = colors, border = border, content = content)
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
