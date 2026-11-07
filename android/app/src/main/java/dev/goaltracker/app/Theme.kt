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
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
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

/**
 * The look of the app. Dark is the signature dark-fantasy look. Light is the same look on parchment.
 * `LIGHT` and `BLACK` keep their stored names so a restored backup still loads: an older "black"
 * choice now falls back to the default look.
 */
enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Parchment"), DARK("Dark fantasy") }

/** Whether the app is currently drawn dark, which can differ from the phone's setting. */
val LocalDarkTheme = compositionLocalOf { false }

/** Dark fantasy: ink-violet surfaces, a violet main color, gold for rewards and ember for danger. */
internal val DarkColors = darkColorScheme(
    primary = Color(0xFFB69CFF),
    onPrimary = Color(0xFF2A0F66),
    primaryContainer = Color(0xFF47299E),
    onPrimaryContainer = Color(0xFFE6DCFF),
    secondary = Color(0xFFF2C14E),
    onSecondary = Color(0xFF3A2A00),
    secondaryContainer = Color(0xFF5C4300),
    onSecondaryContainer = Color(0xFFFFE9B0),
    tertiary = Color(0xFFFF8A6B),
    onTertiary = Color(0xFF4A1000),
    tertiaryContainer = Color(0xFF7A2410),
    onTertiaryContainer = Color(0xFFFFDBD0),
    background = Color(0xFF0E0B1A),
    surface = Color(0xFF0E0B1A),
    onSurface = Color(0xFFEAE4FA),
    surfaceVariant = Color(0xFF2D2650),
    onSurfaceVariant = Color(0xFFC0B8D8),
    surfaceContainerLowest = Color(0xFF0A0813),
    surfaceContainerLow = Color(0xFF161227),
    surfaceContainer = Color(0xFF1C1733),
    surfaceContainerHigh = Color(0xFF241E3F),
    surfaceContainerHighest = Color(0xFF2D2650),
    outline = Color(0xFF8E86AD),
    outlineVariant = Color(0xFF3F3766),
)

/** The same look on parchment. */
internal val LightColors = lightColorScheme(
    primary = Color(0xFF5B3FC4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3DAFF),
    onPrimaryContainer = Color(0xFF25106B),
    secondary = Color(0xFF7A5200),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE2A0),
    onSecondaryContainer = Color(0xFF3D2900),
    tertiary = Color(0xFFA83A1C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDBD0),
    onTertiaryContainer = Color(0xFF4A1000),
    error = Color(0xFF9C1C14),
    background = Color(0xFFF7F0E1),
    surface = Color(0xFFF7F0E1),
    onSurface = Color(0xFF2A2236),
    surfaceVariant = Color(0xFFE6DAC0),
    onSurfaceVariant = Color(0xFF5A5068),
    surfaceContainerLowest = Color(0xFFFFFAF0),
    surfaceContainerLow = Color(0xFFF2E9D6),
    surfaceContainer = Color(0xFFEDE2CB),
    surfaceContainerHigh = Color(0xFFE6DAC0),
    surfaceContainerHighest = Color(0xFFDFD1B5),
    outline = Color(0xFF7C7288),
    outlineVariant = Color(0xFFCFC2A6),
)

/** The scheme for a mode. Internal so the contrast test can check every one. */
internal fun colorSchemeFor(dark: Boolean) = if (dark) DarkColors else LightColors

/** Fixed palettes, because the heatmap's steps were chosen against these surfaces. */
@Composable
fun GoalTrackerTheme(
    mode: ThemeMode = ThemeMode.DARK,
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
    val context = LocalContext.current
    val motion = Feel.motion && !Feel.reducedMotion(context)
    CompositionLocalProvider(LocalDarkTheme provides dark, LocalMotion provides motion) {
        MaterialTheme(
            colorScheme = colorSchemeFor(dark),
            shapes = SoftShapes,
            typography = SoftTypography,
            content = content,
        )
    }
}

/** Display face for titles and big numbers. Body text stays in the system font so it reads well small. */
internal val DisplayFont = FontFamily(
    Font(R.font.cinzel, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.cinzel, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.cinzel, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.cinzel, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
)

/** Cinzel for the big levels of text, the system font for everything people read in bulk. */
internal val SoftTypography = Typography().let {
    it.copy(
        displayLarge = it.displayLarge.copy(fontFamily = DisplayFont, fontWeight = FontWeight.Bold),
        displayMedium = it.displayMedium.copy(fontFamily = DisplayFont, fontWeight = FontWeight.Bold),
        displaySmall = it.displaySmall.copy(fontFamily = DisplayFont, fontWeight = FontWeight.Bold),
        headlineLarge = it.headlineLarge.copy(fontFamily = DisplayFont, fontWeight = FontWeight.Bold),
        headlineMedium = it.headlineMedium.copy(fontFamily = DisplayFont, fontWeight = FontWeight.Bold),
        headlineSmall = it.headlineSmall.copy(fontFamily = DisplayFont, fontWeight = FontWeight.Bold),
        titleLarge = it.titleLarge.copy(fontFamily = DisplayFont, fontWeight = FontWeight.Bold),
        titleMedium = it.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = it.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Rounded corners for chips, buttons, sheets and dialogs. Cards use the stone-tablet shape below. */
internal val SoftShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** A tablet: small corners on two sides and big ones on the others, so cards feel carved, not generic. */
internal val TabletShape = RoundedCornerShape(topStart = 6.dp, topEnd = 22.dp, bottomEnd = 6.dp, bottomStart = 22.dp)

/** Card colors: a deep surface with no shadow. */
@Composable
fun softCardColors(): CardColors = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
)

/** Card colors for the cards that matter most (today's hero card, the boss): a lifted surface. */
@Composable
fun heroCardColors(): CardColors = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
)

/**
 * The app's card. It has the tablet shape and a faint outline instead of a shadow. `glow` swaps the
 * outline for a gold one, for cards that hold something worth noticing.
 */
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    colors: CardColors = softCardColors(),
    glow: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val border = if (glow) BorderStroke(1.5.dp, scheme.secondary.copy(alpha = 0.7f))
    else BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.7f))
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, shape = TabletShape, colors = colors, border = border, content = content)
    } else {
        Card(modifier = modifier, shape = TabletShape, colors = colors, border = border, content = content)
    }
}

/** One-hue steps for 1, 2, 3, and 4+ logs: violet that brightens on dark and deepens on parchment. */
private val HeatLight = listOf(Color(0xFFB9A4F5), Color(0xFF8466E0), Color(0xFF5B3FC4), Color(0xFF2F1A80))
private val HeatDark = listOf(Color(0xFF47299E), Color(0xFF7152D6), Color(0xFFA98BFF), Color(0xFFE6DCFF))

@Composable
fun heatColor(count: Int): Color {
    val dark = LocalDarkTheme.current
    if (count <= 0) return if (dark) Color(0xFF2D2650) else Color(0xFFE6DAC0)
    return (if (dark) HeatDark else HeatLight)[minOf(count, 4) - 1]
}
