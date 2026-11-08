package dev.goaltracker.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * The look of the app. Dark is the signature dark-fantasy look. Light is the same look on parchment.
 * `LIGHT` and `BLACK` keep their stored names so a restored backup still loads: an older "black"
 * choice now falls back to the default look.
 */
enum class ThemeMode(val label: String) {
    SYSTEM("System"), LIGHT("Parchment"), DARK("Dark fantasy"),
    PIXEL("Retro pixel"), NEON("Neon"), CLEAN("Clean"),
}

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

/** Retro pixel: a night-blue console with bright green, coin yellow and red. */
internal val PixelColors = darkColorScheme(
    primary = Color(0xFF5BE38A),
    onPrimary = Color(0xFF00391A),
    primaryContainer = Color(0xFF1F6B3F),
    onPrimaryContainer = Color(0xFFC9FFD9),
    secondary = Color(0xFFFFCD75),
    onSecondary = Color(0xFF3A2600),
    secondaryContainer = Color(0xFF6A4A00),
    onSecondaryContainer = Color(0xFFFFE9C2),
    tertiary = Color(0xFFFF8A65),
    onTertiary = Color(0xFF4A1000),
    tertiaryContainer = Color(0xFF8A2E14),
    onTertiaryContainer = Color(0xFFFFDBD0),
    error = Color(0xFFFF8A80),
    background = Color(0xFF1A1C2C),
    surface = Color(0xFF1A1C2C),
    onSurface = Color(0xFFF4F4F4),
    surfaceVariant = Color(0xFF333C57),
    onSurfaceVariant = Color(0xFFB4C8D6),
    surfaceContainerLowest = Color(0xFF12131F),
    surfaceContainerLow = Color(0xFF22243A),
    surfaceContainer = Color(0xFF292C45),
    surfaceContainerHigh = Color(0xFF2F3550),
    surfaceContainerHighest = Color(0xFF333C57),
    outline = Color(0xFF8DA2B8),
    outlineVariant = Color(0xFF566C86),
)

/** Neon: near-black glass with cyan light, magenta rewards and amber danger. */
internal val NeonColors = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F58),
    onPrimaryContainer = Color(0xFFB3F7FF),
    secondary = Color(0xFFFF6AD5),
    onSecondary = Color(0xFF3D0033),
    secondaryContainer = Color(0xFF5E0B50),
    onSecondaryContainer = Color(0xFFFFD6F5),
    tertiary = Color(0xFFFFB020),
    onTertiary = Color(0xFF3D2600),
    tertiaryContainer = Color(0xFF5C3A00),
    onTertiaryContainer = Color(0xFFFFE2B0),
    error = Color(0xFFFF6B81),
    background = Color(0xFF05060B),
    surface = Color(0xFF05060B),
    onSurface = Color(0xFFE0F7FA),
    surfaceVariant = Color(0xFF1B2338),
    onSurfaceVariant = Color(0xFF9CC0CC),
    surfaceContainerLowest = Color(0xFF020306),
    surfaceContainerLow = Color(0xFF0B0E18),
    surfaceContainer = Color(0xFF0F1320),
    surfaceContainerHigh = Color(0xFF141A2B),
    surfaceContainerHighest = Color(0xFF1B2338),
    outline = Color(0xFF5E8794),
    outlineVariant = Color(0xFF1F3540),
)

/** Clean, light: plain greys and one calm blue. */
internal val CleanLightColors = lightColorScheme(
    primary = Color(0xFF2F5BD3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE5FF),
    onPrimaryContainer = Color(0xFF0A2366),
    secondary = Color(0xFF7A5000),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE6B8),
    onSecondaryContainer = Color(0xFF2E1E00),
    tertiary = Color(0xFFB3261E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDAD6),
    onTertiaryContainer = Color(0xFF410002),
    error = Color(0xFFB3261E),
    background = Color(0xFFFAFAFB),
    surface = Color(0xFFFAFAFB),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE8E8EC),
    onSurfaceVariant = Color(0xFF4A4A55),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F3F6),
    surfaceContainer = Color(0xFFEEEEF1),
    surfaceContainerHigh = Color(0xFFE8E8EC),
    surfaceContainerHighest = Color(0xFFE2E2E7),
    outline = Color(0xFF77777F),
    outlineVariant = Color(0xFFD4D4DA),
)

/** Clean, dark: the same calm look at night. */
internal val CleanDarkColors = darkColorScheme(
    primary = Color(0xFF9DB7FF),
    onPrimary = Color(0xFF0A2366),
    primaryContainer = Color(0xFF27407F),
    onPrimaryContainer = Color(0xFFDCE5FF),
    secondary = Color(0xFFF0C060),
    onSecondary = Color(0xFF2E1E00),
    secondaryContainer = Color(0xFF4A3500),
    onSecondaryContainer = Color(0xFFFFE6B8),
    tertiary = Color(0xFFFFB4AB),
    onTertiary = Color(0xFF690005),
    tertiaryContainer = Color(0xFF93000A),
    onTertiaryContainer = Color(0xFFFFDAD6),
    error = Color(0xFFFFB4AB),
    background = Color(0xFF121214),
    surface = Color(0xFF121214),
    onSurface = Color(0xFFE6E6EA),
    surfaceVariant = Color(0xFF2E2E34),
    onSurfaceVariant = Color(0xFFB4B4BE),
    surfaceContainerLowest = Color(0xFF0C0C0E),
    surfaceContainerLow = Color(0xFF1A1A1D),
    surfaceContainer = Color(0xFF1F1F23),
    surfaceContainerHigh = Color(0xFF26262B),
    surfaceContainerHighest = Color(0xFF2E2E34),
    outline = Color(0xFF8A8A94),
    outlineVariant = Color(0xFF3A3A42),
)

/** Whether a look is dark. System and Clean follow the phone. */
internal fun ThemeMode.isDark(systemDark: Boolean) = when (this) {
    ThemeMode.SYSTEM, ThemeMode.CLEAN -> systemDark
    ThemeMode.LIGHT -> false
    else -> true
}

/** The colors of a look. Internal so the contrast test can check every one. */
internal fun ThemeMode.scheme(systemDark: Boolean): ColorScheme = when (this) {
    ThemeMode.SYSTEM -> if (systemDark) DarkColors else LightColors
    ThemeMode.LIGHT -> LightColors
    ThemeMode.DARK -> DarkColors
    ThemeMode.PIXEL -> PixelColors
    ThemeMode.NEON -> NeonColors
    ThemeMode.CLEAN -> if (systemDark) CleanDarkColors else CleanLightColors
}

/** The style a look draws in: shapes, fonts, art, bars and effects. */
internal fun ThemeMode.style(): Style = when (this) {
    ThemeMode.PIXEL -> Style.PIXEL
    ThemeMode.NEON -> Style.NEON
    ThemeMode.CLEAN -> Style.CLEAN
    else -> Style.FANTASY
}

/** The colors of the look in use, for the heatmap. */
val LocalScheme = compositionLocalOf { DarkColors }

/** Fixed palettes, because the heatmap's steps were chosen against these surfaces. */
@Composable
fun GoalTrackerTheme(
    mode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = mode.isDark(systemDark)
    val scheme = mode.scheme(systemDark)
    val style = mode.style()
    SideEffect { Feel.style = style }
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
    CompositionLocalProvider(LocalDarkTheme provides dark, LocalScheme provides scheme, LocalMotion provides motion, LocalStyle provides style) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = shapesFor(style),
            typography = typographyFor(style),
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
    val style = LocalStyle.current
    val shape = cardShape(style)
    val border = when (style) {
        Style.FANTASY -> if (glow) BorderStroke(1.5.dp, scheme.secondary.copy(alpha = 0.7f))
        else BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.7f))
        // Chunky outline, like a game window.
        Style.PIXEL -> BorderStroke(2.dp, if (glow) scheme.secondary else scheme.outlineVariant)
        // A thin lit edge.
        Style.NEON -> BorderStroke(1.dp, (if (glow) scheme.secondary else scheme.primary).copy(alpha = if (glow) 0.9f else 0.45f))
        // No outline unless it matters.
        Style.CLEAN -> if (glow) BorderStroke(1.dp, scheme.secondary.copy(alpha = 0.6f)) else null
    }
    val shadow = if (style == Style.PIXEL) scheme.surfaceContainerLowest else Color.Unspecified
    val glowColor = if (glow) scheme.secondary else scheme.primary
    val styled = when (style) {
        // A hard drop shadow, offset down and right, with no blur.
        Style.PIXEL -> modifier.drawBehind {
            val step = 4.dp.toPx()
            drawRect(shadow, Offset(step, step), size)
        }
        // A soft colored light around the panel.
        Style.NEON -> modifier.shadow(10.dp, shape, ambientColor = glowColor, spotColor = glowColor)
        else -> modifier
    }
    if (onClick != null) {
        Card(onClick = onClick, modifier = styled, shape = shape, colors = colors, border = border, content = content)
    } else {
        Card(modifier = styled, shape = shape, colors = colors, border = border, content = content)
    }
}

/** One-hue steps for 1, 2, 3, and 4+ logs: violet that brightens on dark and deepens on parchment. */
private val HeatLight = listOf(Color(0xFFB9A4F5), Color(0xFF8466E0), Color(0xFF5B3FC4), Color(0xFF2F1A80))
private val HeatDark = listOf(Color(0xFF47299E), Color(0xFF7152D6), Color(0xFFA98BFF), Color(0xFFE6DCFF))

@Composable
fun heatColor(count: Int): Color {
    val dark = LocalDarkTheme.current
    val s = LocalScheme.current
    if (s === DarkColors || s === LightColors) {
        if (count <= 0) return if (dark) Color(0xFF2D2650) else Color(0xFFE6DAC0)
        return (if (dark) HeatDark else HeatLight)[minOf(count, 4) - 1]
    }
    // Other looks: steps from the look's own main color, brightening on dark and deepening on light.
    if (count <= 0) return s.surfaceContainerHighest
    val steps = if (dark) {
        listOf(s.primaryContainer, lerp(s.primaryContainer, s.primary, 0.5f), s.primary, s.onPrimaryContainer)
    } else {
        listOf(lerp(s.primaryContainer, s.primary, 0.3f), lerp(s.primaryContainer, s.primary, 0.65f), s.primary, s.onPrimaryContainer)
    }
    return steps[minOf(count, 4) - 1]
}
