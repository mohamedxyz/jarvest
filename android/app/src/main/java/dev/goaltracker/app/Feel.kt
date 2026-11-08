package dev.goaltracker.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** The small moments the app can mark with a buzz and a sound. */
enum class Cue { TASK, HABIT, UNDO, GOAL, TREAT }

/**
 * "Feel" settings: haptics, sounds, and the remembered intro/settings state. They are
 * saved on the phone only (SharedPreferences), because they describe this phone's
 * speaker and vibration motor, not your goals. Backups don't carry them.
 */
object Feel {
    private var prefs: android.content.SharedPreferences? = null

    var haptics by mutableStateOf(true)
        private set
    var sounds by mutableStateOf(false)
        private set

    /** Which set of sounds plays when sounds are on. */
    var soundProfile by mutableStateOf(SoundProfile.CHIMES)
        private set

    /** The in-app Motion switch. The phone's "remove animations" setting always wins over it. */
    var motion by mutableStateOf(true)
        private set

    /** Set to true (for example by "Show intro again") to open the intro. */
    var introRequested by mutableStateOf(false)

    /** The look's style, set by the theme. It picks the buzz and the pop. */
    var style by mutableStateOf(Style.FANTASY)

    /** Bumped whenever a goal's target is met, so the big celebration plays once per event. */
    var celebration by mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        val p = context.getSharedPreferences("feel", Context.MODE_PRIVATE)
        prefs = p
        haptics = p.getBoolean("haptics", true)
        sounds = p.getBoolean("sounds", false)
        soundProfile = SoundProfile.entries.firstOrNull { it.name == p.getString("sound_profile", null) } ?: SoundProfile.CHIMES
        motion = p.getBoolean("motion", true)
    }

    fun chooseMotion(on: Boolean) {
        motion = on
        prefs?.edit()?.putBoolean("motion", on)?.apply()
    }

    fun chooseHaptics(on: Boolean) {
        haptics = on
        prefs?.edit()?.putBoolean("haptics", on)?.apply()
    }

    fun chooseSoundProfile(profile: SoundProfile) {
        soundProfile = profile
        prefs?.edit()?.putString("sound_profile", profile.name)?.apply()
    }

    fun chooseSounds(on: Boolean) {
        sounds = on
        prefs?.edit()?.putBoolean("sounds", on)?.apply()
    }

    // Small remembered flags: the intro, and which Settings groups are open.
    fun flag(key: String, default: Boolean): Boolean = prefs?.getBoolean(key, default) ?: default

    fun setFlag(key: String, value: Boolean) {
        prefs?.edit()?.putBoolean(key, value)?.apply()
    }

    /** True when the phone's "animation scale" is 0 (Remove animations / reduced motion). */
    fun reducedMotion(context: Context): Boolean = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (_: Exception) {
        false
    }

    /** Plays the buzz and the tone for [cue], each only if its Settings switch is on. */
    fun cue(view: View?, cue: Cue, force: Boolean = false) {
        if (haptics && view != null) {
            // Each look has its own touch: chunky keys (pixel), crisp clicks (neon), light ticks (clean).
            val kind = when (style) {
                Style.PIXEL -> when (cue) {
                    Cue.UNDO -> HapticFeedbackConstants.CLOCK_TICK
                    Cue.GOAL, Cue.TREAT -> HapticFeedbackConstants.LONG_PRESS
                    else -> HapticFeedbackConstants.KEYBOARD_TAP
                }
                Style.NEON -> when (cue) {
                    Cue.UNDO -> HapticFeedbackConstants.CLOCK_TICK
                    Cue.GOAL, Cue.TREAT -> HapticFeedbackConstants.CONFIRM
                    else -> HapticFeedbackConstants.CONTEXT_CLICK
                }
                Style.CLEAN -> when (cue) {
                    Cue.GOAL, Cue.TREAT -> HapticFeedbackConstants.CONFIRM
                    else -> HapticFeedbackConstants.CLOCK_TICK
                }
                Style.FANTASY -> when (cue) {
                    Cue.UNDO -> HapticFeedbackConstants.CLOCK_TICK
                    Cue.GOAL -> HapticFeedbackConstants.CONFIRM
                    else -> HapticFeedbackConstants.VIRTUAL_KEY
                }
            }
            view.performHapticFeedback(kind)
        }
        if (sounds || force) Tones.play(soundProfile, cue)
    }

    /** A goal's target was met: a bigger moment than an ordinary tick. */
    fun celebrate(view: View?, treat: Boolean = false) {
        celebration++
        cue(view, if (treat) Cue.TREAT else Cue.GOAL)
    }
}

/** A set of sounds the user can choose. Every profile has a sound for each [Cue]. */
enum class SoundProfile(val label: String, val blurb: String) {
    CHIMES("Soft chimes", "Warm, gentle bells"),
    POP("Pop and bubble", "Short, round pops"),
    RETRO("Retro game", "Soft 8-bit blips"),
    WOOD("Wood and click", "Dry, quiet taps"),
}

/**
 * Short tones made in code, so the app needs no sound files. Each profile builds its notes
 * from a different wave shape. To keep them gentle, every note starts with a quick fade-in
 * (no click), fades out, and the sharper waves are smoothed with a low-pass filter.
 */
object Tones {
    private const val RATE = 22050

    /** One note: start pitch, optional end pitch (a slide), length, and silence after it. */
    private class Note(val hz: Double, val ms: Int, val toHz: Double = hz, val gapMs: Int = 0)

    private fun notes(profile: SoundProfile, cue: Cue): List<Note> = when (profile) {
        SoundProfile.CHIMES -> when (cue) {
            Cue.TASK -> listOf(Note(659.0, 380))
            Cue.HABIT -> listOf(Note(523.0, 200, gapMs = 20), Note(784.0, 420))
            Cue.UNDO -> listOf(Note(392.0, 260))
            Cue.GOAL -> listOf(Note(523.0, 160), Note(659.0, 160), Note(784.0, 160), Note(1047.0, 520))
            Cue.TREAT -> listOf(Note(523.0, 140), Note(659.0, 140), Note(784.0, 140), Note(1047.0, 140), Note(1319.0, 620))
        }
        SoundProfile.POP -> when (cue) {
            Cue.TASK -> listOf(Note(420.0, 80, toHz = 760.0))
            Cue.HABIT -> listOf(Note(420.0, 70, toHz = 700.0, gapMs = 30), Note(520.0, 80, toHz = 900.0))
            Cue.UNDO -> listOf(Note(640.0, 90, toHz = 320.0))
            Cue.GOAL -> listOf(Note(400.0, 70, toHz = 700.0, gapMs = 25), Note(480.0, 70, toHz = 820.0, gapMs = 25), Note(600.0, 110, toHz = 1000.0))
            Cue.TREAT -> listOf(Note(400.0, 70, toHz = 700.0, gapMs = 20), Note(500.0, 70, toHz = 850.0, gapMs = 20), Note(620.0, 70, toHz = 1000.0, gapMs = 20), Note(780.0, 160, toHz = 1300.0))
        }
        SoundProfile.RETRO -> when (cue) {
            Cue.TASK -> listOf(Note(587.0, 70))
            Cue.HABIT -> listOf(Note(494.0, 60), Note(740.0, 90))
            Cue.UNDO -> listOf(Note(392.0, 70), Note(294.0, 90))
            Cue.GOAL -> listOf(Note(523.0, 70), Note(659.0, 70), Note(784.0, 120))
            Cue.TREAT -> listOf(Note(523.0, 70), Note(659.0, 70), Note(784.0, 70), Note(1047.0, 70), Note(784.0, 60), Note(1047.0, 220))
        }
        SoundProfile.WOOD -> when (cue) {
            Cue.TASK -> listOf(Note(880.0, 60))
            Cue.HABIT -> listOf(Note(700.0, 55, gapMs = 40), Note(900.0, 70))
            Cue.UNDO -> listOf(Note(500.0, 70))
            Cue.GOAL -> listOf(Note(660.0, 60, gapMs = 50), Note(830.0, 60, gapMs = 50), Note(990.0, 90))
            Cue.TREAT -> listOf(Note(523.0, 80, gapMs = 40), Note(659.0, 80, gapMs = 40), Note(784.0, 80, gapMs = 40), Note(1047.0, 160))
        }
    }

    /** The wave at [phase] (radians). Each profile has its own shape. */
    private fun wave(profile: SoundProfile, phase: Double): Double = when (profile) {
        // A fundamental with two quiet overtones sounds like a bell.
        SoundProfile.CHIMES -> (sin(phase) + 0.35 * sin(2 * phase) + 0.12 * sin(3 * phase)) / 1.47
        SoundProfile.POP -> sin(phase)
        // A narrow square wave, smoothed below. The classic 8-bit sound without the sting.
        SoundProfile.RETRO -> if ((phase / (2 * PI)) % 1.0 < 0.25) 1.0 else -1.0
        // A short tone with an inharmonic overtone, like a wooden block.
        SoundProfile.WOOD -> (sin(phase) + 0.5 * sin(2.4 * phase)) / 1.5
    }

    private fun gain(profile: SoundProfile) = when (profile) {
        SoundProfile.CHIMES -> 0.30
        SoundProfile.POP -> 0.32
        SoundProfile.RETRO -> 0.20
        SoundProfile.WOOD -> 0.35
    }

    /** How fast a note dies away. Bigger is shorter and drier. */
    private fun decay(profile: SoundProfile) = when (profile) {
        SoundProfile.CHIMES -> 3.0
        SoundProfile.POP -> 5.0
        SoundProfile.RETRO -> 3.0
        SoundProfile.WOOD -> 9.0
    }

    /** How much of the sharpness is kept: 1.0 is none removed, lower is softer. */
    private fun brightness(profile: SoundProfile) = when (profile) {
        SoundProfile.CHIMES -> 1.0
        SoundProfile.POP -> 1.0
        SoundProfile.RETRO -> 0.30
        SoundProfile.WOOD -> 0.65
    }

    private fun render(profile: SoundProfile, cue: Cue): ShortArray {
        val out = ArrayList<Short>()
        val volume = gain(profile) * Short.MAX_VALUE
        val k = decay(profile)
        val smooth = brightness(profile)
        val fadeIn = RATE * 6 / 1000
        val fadeOut = RATE * 10 / 1000
        for (note in notes(profile, cue)) {
            val n = RATE * note.ms / 1000
            var phase = 0.0
            var filtered = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / n
                phase += 2 * PI * (note.hz + (note.toHz - note.hz) * t) / RATE
                filtered += smooth * (wave(profile, phase) - filtered)
                // Fade in over 6 ms (no click), die away, and fade out over the last 10 ms.
                val envelope = minOf(1.0, i.toDouble() / fadeIn) * exp(-k * t) * minOf(1.0, (n - i).toDouble() / fadeOut)
                out.add((filtered * envelope * volume).toInt().toShort())
            }
            repeat(RATE * note.gapMs / 1000) { out.add(0) }
        }
        return out.toShortArray()
    }

    fun play(profile: SoundProfile, cue: Cue) {
        val data = render(profile, cue)
        // Played on its own thread so a tap never waits for audio.
        Thread {
            try {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(data.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(data, 0, data.size)
                track.play()
                Thread.sleep(data.size * 1000L / RATE + 80)
                track.release()
            } catch (_: Exception) {
                // No sound is better than a crash.
            }
        }.start()
    }
}

/**
 * A quick "pop" (grow, then settle) each time [on] turns true. Skipped on the first
 * draw, and when the phone has animations turned off.
 */
@Composable
fun Modifier.popWhen(on: Boolean): Modifier {
    val context = LocalContext.current
    val scale = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(on) {
        if (first) {
            first = false
            return@LaunchedEffect
        }
        if (on && !Feel.reducedMotion(context)) {
            when (Feel.style) {
                // Jumps in steps, like a sprite.
                Style.PIXEL -> for (step in listOf(1.3f, 1.15f, 1f)) {
                    scale.snapTo(step)
                    kotlinx.coroutines.delay(70)
                }
                Style.NEON -> {
                    scale.animateTo(1.22f, tween(60))
                    scale.animateTo(1f, tween(140))
                }
                Style.CLEAN -> {
                    scale.animateTo(1.1f, tween(90))
                    scale.animateTo(1f, tween(160))
                }
                Style.FANTASY -> {
                    scale.animateTo(1.35f, tween(80))
                    scale.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
                }
            }
        }
    }
    return scale(scale.value)
}

/**
 * The bigger celebration for a met target: a check mark that swells and fades.
 * Draws nothing at rest, and nothing at all when animations are off.
 */
@Composable
fun CelebrationBurst() {
    val context = LocalContext.current
    val trigger = Feel.celebration
    val style = LocalStyle.current
    if (style != Style.FANTASY) {
        StyledCelebration(style, trigger)
        return
    }
    val scale = remember { Animatable(0.3f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger == 0 || Feel.reducedMotion(context)) return@LaunchedEffect
        alpha.snapTo(1f)
        scale.snapTo(0.3f)
        scale.animateTo(1.4f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow))
        alpha.animateTo(0f, tween(450))
    }
    if (alpha.value > 0f) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.CheckCircle,
                null,
                Modifier.size(120.dp).scale(scale.value).alpha(alpha.value),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** A checkbox that pops, buzzes, and sounds when ticked, as the Settings switches allow. */
@Composable
fun TickBox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cue: Cue = Cue.TASK,
) {
    val view = LocalView.current
    androidx.compose.material3.Checkbox(
        checked = checked,
        onCheckedChange = {
            Feel.cue(view, if (it) cue else Cue.UNDO)
            onCheckedChange(it)
        },
        modifier = modifier.popWhen(checked),
        enabled = enabled,
    )
}
