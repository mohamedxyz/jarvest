package dev.goaltracker.app

import androidx.glance.material3.ColorProviders

/**
 * The widgets use the same palettes as the app: dark fantasy on a dark phone, parchment on a light one.
 * Without this they would take the wallpaper colors and look like another app.
 */
internal val WidgetColors = ColorProviders(light = LightColors, dark = DarkColors)
