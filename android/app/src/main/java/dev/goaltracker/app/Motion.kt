package dev.goaltracker.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * True when screens may animate. It is false when the phone's "remove animations" setting is on
 * or the in-app Motion switch (Settings > Look and feel) is off. Every animation checks this.
 */
val LocalMotion = compositionLocalOf { true }

/** A smooth tween, or an instant jump when motion is off. */
fun <T> motionTween(on: Boolean, millis: Int = 300, delay: Int = 0): FiniteAnimationSpec<T> =
    if (on) tween(millis, delay, FastOutSlowInEasing) else snap()

/** Lets a list row slide and fade when items are added, removed or moved. Off when motion is off. */
@Composable
fun LazyItemScope.softItem(): Modifier = if (LocalMotion.current) Modifier.animateItem() else Modifier
