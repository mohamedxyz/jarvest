package dev.goaltracker.app

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Checks WCAG contrast for the text/background pairs in Theme.kt, in every mode and
 * accent. Normal text needs a ratio of at least 4.5. Run it with
 * `./gradlew testDebugUnitTest`.
 */
class ContrastTest {
    private fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun ratio(a: Color, b: Color): Double {
        val x = luminance(a)
        val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    @Test
    fun everyTextPairIsReadable() {
        val failures = mutableListOf<String>()
        // Every look, on a light phone and a dark one (System and Clean follow the phone).
        val setups = ThemeMode.entries.flatMap { listOf(it to false, it to true) }
        for ((mode, phoneDark) in setups) {
            val name = mode.label + if (phoneDark) " (dark phone)" else ""
            run {
                val s = mode.scheme(systemDark = phoneDark)
                val surfaces = listOf(
                    "background" to s.background,
                    "surface" to s.surface,
                    "container" to s.surfaceContainer,
                    "containerHigh" to s.surfaceContainerHigh,
                    "containerLow" to s.surfaceContainerLow,
                )
                val pairs = mutableListOf(
                    "onPrimary/primary" to (s.onPrimary to s.primary),
                    "onPrimaryContainer/primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
                    "onSecondaryContainer/secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
                    "onSurfaceVariant/surfaceVariant" to (s.onSurfaceVariant to s.surfaceVariant),
                    "onSecondary/secondary" to (s.onSecondary to s.secondary),
                    "onTertiaryContainer/tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
                    "onTertiary/tertiary" to (s.onTertiary to s.tertiary),
                )
                for ((surfaceName, surface) in surfaces) {
                    pairs += "onSurface/$surfaceName" to (s.onSurface to surface)
                    pairs += "onSurfaceVariant/$surfaceName" to (s.onSurfaceVariant to surface)
                    pairs += "primary/$surfaceName" to (s.primary to surface)
                    pairs += "error/$surfaceName" to (s.error to surface)
                    pairs += "secondary/$surfaceName" to (s.secondary to surface)
                    pairs += "tertiary/$surfaceName" to (s.tertiary to surface)
                }
                for ((label, colors) in pairs) {
                    val r = ratio(colors.first, colors.second)
                    if (r < 4.5) failures += "$name $label = ${"%.2f".format(r)}"
                }
            }
        }
        assertTrue("Low contrast:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
