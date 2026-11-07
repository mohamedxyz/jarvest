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
    GROVE("Emerald grove"), BLOOD("Blood moon"), FROST("Frost"), ABYSS("Abyss"), DAWN("Dawn"),
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

/** A dark look built on the dark-fantasy one: its own main color and surfaces, the same gold and ember. */
private fun darkLook(
    primary: Long, onPrimary: Long, primaryContainer: Long, onPrimaryContainer: Long,
    surfaces: List<Long>, onSurface: Long, onSurfaceVariant: Long, outline: Long, outlineVariant: Long,
): ColorScheme {
    val (lowest, base, low, mid, high, highest) = surfaces.map { Color(it) }
    return DarkColors.copy(
        primary = Color(primary), onPrimary = Color(onPrimary),
        primaryContainer = Color(primaryContainer), onPrimaryContainer = Color(onPrimaryContainer),
        background = base, surface = base, surfaceVariant = highest,
        surfaceContainerLowest = lowest, surfaceContainerLow = low, surfaceContainer = mid,
        surfaceContainerHigh = high, surfaceContainerHighest = highest,
        onSurface = Color(onSurface), onSurfaceVariant = Color(onSurfaceVariant),
        outline = Color(outline), outlineVariant = Color(outlineVariant),
    )
}

private operator fun <T> List<T>.component6() = this[5]

/** Moss and emerald. */
internal val GroveColors = darkLook(
    0xFF7EDCA6, 0xFF003920, 0xFF1E5A3C, 0xFFC8F5DA,
    listOf(0xFF060C09, 0xFF0A120E, 0xFF111C16, 0xFF16241C, 0xFF1D2D24, 0xFF25382D),
    0xFFE2F2E8, 0xFFB3C9BB, 0xFF809A8A, 0xFF34483C,
)

/** Deep crimson. */
internal val BloodColors = darkLook(
    0xFFFF99A4, 0xFF5C0016, 0xFF7E1A2C, 0xFFFFD9DD,
    listOf(0xFF0D0507, 0xFF14090C, 0xFF1E0F13, 0xFF26141A, 0xFF301A21, 0xFF3B222A),
    0xFFF6E3E6, 0xFFD4B8BE, 0xFFA3858C, 0xFF4E3038,
)

/** Ice blue and steel. */
internal val FrostColors = darkLook(
    0xFF8FD0FF, 0xFF00344F, 0xFF1B4E73, 0xFFCFE8FF,
    listOf(0xFF05090D, 0xFF091017, 0xFF0F1A24, 0xFF14212D, 0xFF1B2A38, 0xFF233444),
    0xFFE3EEF7, 0xFFB3C3D1, 0xFF7F92A3, 0xFF314253,
)

/** Dark fantasy on pure black, which saves battery on OLED screens. */
internal val AbyssColors = darkLook(
    0xFFB69CFF, 0xFF2A0F66, 0xFF47299E, 0xFFE6DCFF,
    listOf(0xFF000000, 0xFF000000, 0xFF0B0914, 0xFF120F20, 0xFF1A162C, 0xFF241E3B),
    0xFFEAE4FA, 0xFFC0B8D8, 0xFF8E86AD, 0xFF3A3360,
)

/** Rose on warm sand: a light look. */
internal val DawnColors = LightColors.copy(
    primary = Color(0xFF9C2F5A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E3),
    onPrimaryContainer = Color(0xFF3E0019),
    background = Color(0xFFFFF4EE),
    surface = Color(0xFFFFF4EE),
    onSurface = Color(0xFF2E1F24),
    surfaceVariant = Color(0xFFF0D8CD),
    onSurfaceVariant = Color(0xFF5E4A50),
    surfaceContainerLowest = Color(0xFFFFFBF8),
    surfaceContainerLow = Color(0xFFFBEBE3),
    surfaceContainer = Color(0xFFF6E2D8),
    surfaceContainerHigh = Color(0xFFF0D8CD),
    surfaceContainerHighest = Color(0xFFE9CEC2),
    outline = Color(0xFF85707A),
    outlineVariant = Color(0xFFD8BFB6),
)

/** Whether a look is dark. System follows the phone. */
internal fun ThemeMode.isDark(systemDark: Boolean) = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT, ThemeMode.DAWN -> false
    else -> true
}

/** The colors of a look. Internal so the contrast test can check every one. */
internal fun ThemeMode.scheme(systemDark: Boolean): ColorScheme = when (this) {
    ThemeMode.SYSTEM -> if (systemDark) DarkColors else LightColors
    ThemeMode.LIGHT -> LightColors
    ThemeMode.DARK -> DarkColors
    ThemeMode.GROVE -> GroveColors
    ThemeMode.BLOOD -> BloodColors
    ThemeMode.FROST -> FrostColors
    ThemeMode.ABYSS -> AbyssColors
    ThemeMode.DAWN -> DawnColors
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
    CompositionLocalProvider(LocalDarkTheme provides dark, LocalScheme provides scheme, LocalMotion provides motion) {
        MaterialTheme(
            colorScheme = scheme,
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
