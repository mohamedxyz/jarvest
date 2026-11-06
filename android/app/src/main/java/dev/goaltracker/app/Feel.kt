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
enum class Cue { TASK, HABIT, UNDO, GOAL }

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

    /** The in-app Motion switch. The phone's "remove animations" setting always wins over it. */
    var motion by mutableStateOf(true)
        private set

    /** Set to true (for example by "Show intro again") to open the intro. */
    var introRequested by mutableStateOf(false)

    /** Bumped whenever a goal's target is met, so the big celebration plays once per event. */
    var celebration by mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        val p = context.getSharedPreferences("feel", Context.MODE_PRIVATE)
        prefs = p
        haptics = p.getBoolean("haptics", true)
        sounds = p.getBoolean("sounds", false)
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
            val kind = when (cue) {
                Cue.UNDO -> HapticFeedbackConstants.CLOCK_TICK
                Cue.GOAL -> HapticFeedbackConstants.CONFIRM
                else -> HapticFeedbackConstants.VIRTUAL_KEY
            }
            view.performHapticFeedback(kind)
        }
        if (sounds || force) Tones.play(cue)
    }

    /** A goal's target was met: a bigger moment than an ordinary tick. */
    fun celebrate(view: View?) {
        celebration++
        cue(view, Cue.GOAL)
    }
}

/**
 * Short tones made in code, so the app needs no sound files. Each tone is a sine wave
 * that fades out. A task is one note, a habit two rising notes, undo one low note,
 * and a goal three rising notes.
 */
object Tones {
    private const val RATE = 22050

    private fun notes(cue: Cue): List<Pair<Double, Int>> = when (cue) {
        Cue.TASK -> listOf(660.0 to 90)
        Cue.HABIT -> listOf(523.0 to 80, 784.0 to 110)
        Cue.UNDO -> listOf(330.0 to 100)
        Cue.GOAL -> listOf(523.0 to 90, 659.0 to 90, 784.0 to 160)
    }

    fun play(cue: Cue) {
        val samples = ArrayList<Short>()
        for ((hz, ms) in notes(cue)) {
            val n = RATE * ms / 1000
            for (i in 0 until n) {
                val fade = exp(-4.0 * i / n)
                samples.add((sin(2 * PI * hz * i / RATE) * fade * 0.4 * Short.MAX_VALUE).toInt().toShort())
            }
        }
        val data = samples.toShortArray()
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
            scale.animateTo(1.35f, tween(80))
            scale.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
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
