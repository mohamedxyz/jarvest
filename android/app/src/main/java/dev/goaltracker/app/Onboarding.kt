package dev.goaltracker.app

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// Icons
// ---------------------------------------------------------------------------

/** Puts an item's emoji (if it has one) in front of its title. */
fun String.withIcon(icon: String?): String = if (icon.isNullOrBlank()) this else "$icon $this"

private val iconChoices = listOf(
    "📚", "🏃", "💧", "🧘", "💪", "🎯", "📝", "💰", "🍎", "😴", "🎨", "🎵",
    "🧹", "📞", "🛒", "✈️", "💻", "🌱", "❤️", "⭐",
)

/** A line showing the current icon, with a button that opens a small picker. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IconPickerRow(current: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { open = !open }) {
            Text(if (current == null) "Add an icon" else "Icon: $current  (change)")
        }
        if (open) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                iconChoices.forEach { emoji ->
                    FilterChip(
                        selected = emoji == current,
                        onClick = { onPick(emoji); open = false },
                        label = { Text(emoji) },
                        modifier = Modifier.label("Use icon $emoji"),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    custom, { custom = it.take(8) },
                    Modifier.weight(1f),
                    label = { Text(stringResource(R.string.ui_or_type_any_emoji)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                )
                TextButton(enabled = custom.isNotBlank(), onClick = { onPick(custom.trim()); open = false; custom = "" }) { Text(stringResource(R.string.ui_set)) }
            }
            if (current != null) TextButton(onClick = { onPick(null); open = false }) { Text(stringResource(R.string.ui_remove_icon)) }
        }
    }
}

// ---------------------------------------------------------------------------
// Sample data and template bundles
// ---------------------------------------------------------------------------

private class Bundle(val key: String, val label: String)

private val bundles = listOf(
    Bundle("get_fit", "Get fit"),
    Bundle("learn_skill", "Learn a skill"),
    Bundle("school_year", "School year"),
)

/** One-tap bundles of goals and starter tasks. [onDone] runs after one is added. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BundleChips(controller: AppController, onDone: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle("Bundles")
        Text(
            "Two or three goals and a few starter tasks in one tap. You can edit or delete any of them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            bundles.forEach { bundle ->
                AssistChip(onClick = {
                    val made = controller.change { createTemplate(bundle.key, controller.today) }
                    if (made != null) {
                        controller.message = "Added $made items from \"${bundle.label}\"."
                        onDone()
                    }
                }, label = { Text(bundle.label) })
            }
        }
    }
}

/** What the empty Today screen offers: bundles, and a way to try the app with sample data. */
@Composable
fun FirstRunHelpers(controller: AppController) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BundleChips(controller, onDone = {})
        OutlinedButton(onClick = { controller.act { createSampleData(controller.today) } }) {
            Text(stringResource(R.string.ui_try_it_with_sample_data))
        }
    }
}

/** Shown while sample items exist: they can be cleared in one tap, or kept as real. */
@Composable
fun SampleBanner(controller: AppController) {
    val has = remember(controller.version) { controller.read(false) { hasSampleData() } }
    if (!has) return
    SoftCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(
                "Sample data is on. It is only for trying the app out, and it earns no rewards.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Row {
                TextButton(onClick = {
                    val removed = controller.change { clearSampleData() }
                    if (removed != null) controller.message = "Sample data cleared."
                }) { Text(stringResource(R.string.ui_clear_sample_data)) }
                TextButton(onClick = {
                    if (controller.act { keepSampleData() }) controller.message = "Sample items are now yours."
                }) { Text(stringResource(R.string.ui_keep_it)) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// First-run intro
// ---------------------------------------------------------------------------

private class IntroStep(val title: String, val body: String)

private val introSteps = listOf(
    IntroStep(
        "Habits, goals and tasks",
        "A habit is something you repeat, like reading every day. A goal is a target to reach, like 300 pages, " +
            "broken into milestones. A task is a one-off to-do with an optional due date. Today shows all three.",
    ),
    IntroStep(
        "Milestones",
        "Break a big goal into milestones. Each one also shows up in your Tasks tab, and ticking either one ticks both.",
    ),
    IntroStep(
        "Rewards and the countdown",
        "Fill a jar with treats you enjoy. Each time you meet a target you may win one, and the countdown on the Rewards tab " +
            "shows when the next win is guaranteed.",
    ),
    IntroStep(
        "Back up your data",
        "Everything stays on this phone, and nothing is uploaded. Uninstalling the app deletes it, " +
            "so save a backup file from Settings now and then.",
    ),
)

/** True on a fresh install. People who already have data never see the intro on their own. */
private fun needsIntro(controller: AppController): Boolean {
    if (Feel.flag("intro_seen", false)) return false
    val empty = controller.read(true) { listGoals(false).isEmpty() && listGoals(true).isEmpty() } &&
        controller.read(true) { listTasks(dev.goaltracker.core.TaskFilter.ALL, dev.goaltracker.core.TaskSort.DUE, controller.today).isEmpty() }
    // An upgrade over an older version already has data: skip the intro for good.
    if (!empty) Feel.setFlag("intro_seen", true)
    return empty
}

/** Chooses between the first-run intro and the app. */
@Composable
fun AppEntry(controller: AppController, startOnNewTask: Boolean = false, startOnTasks: Boolean = false) {
    var firstRun by rememberSaveable { mutableStateOf(needsIntro(controller)) }
    if (firstRun || Feel.introRequested) {
        IntroScreen(controller, onDone = {
            Feel.setFlag("intro_seen", true)
            Feel.introRequested = false
            firstRun = false
        })
    } else {
        AppRoot(controller, startOnNewTask, startOnTasks)
    }
}

@Composable
fun IntroScreen(controller: AppController, onDone: () -> Unit) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val last = index == introSteps.lastIndex
    val step = introSteps[index]
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().padding(24.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (!last) TextButton(onClick = onDone) { Text(stringResource(R.string.ui_skip)) }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Step ${index + 1} of ${introSteps.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(step.title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Text(
                step.body,
                Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        // Dots show where you are. They are hidden from TalkBack because the "Step n of m" text says it.
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
            introSteps.indices.forEach { i ->
                Box(
                    Modifier.padding(4.dp).size(8.dp).background(
                        if (i == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    ),
                )
            }
        }
        if (last) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_start_fresh)) }
                OutlinedButton(onClick = {
                    controller.act { createSampleData(controller.today) }
                    onDone()
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_try_it_with_sample_data)) }
                TextButton(onClick = { index-- }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_back)) }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = index > 0, onClick = { index-- }) { Text(stringResource(R.string.ui_back)) }
                Button(onClick = { index++ }) { Text(stringResource(R.string.ui_next)) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Settings: groups, feel switches, help and what's new
// ---------------------------------------------------------------------------

/** A Settings section that folds away. Whether it is open is remembered on the phone. */
@Composable
fun SettingsGroup(title: String, id: String, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(Feel.flag("group_$id", true)) }
    Column {
        Row(
            Modifier.fillMaxWidth()
                .clickable(onClickLabel = if (open) "Collapse $title" else "Expand $title") {
                    open = !open
                    Feel.setFlag("group_$id", open)
                }
                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
        }
        if (open) {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp), content = content)
        }
    }
}

/** Haptics and sound switches, with a preview of each sound. */
@Composable
fun FeelSettings() {
    val view = LocalView.current
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Feedback")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.ui_vibrate_lightly_when_you_tick_un), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(Feel.haptics, { Feel.chooseHaptics(it) }, modifier = Modifier.label("Haptic feedback"))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.ui_play_a_short_sound_when_you_tick), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(Feel.sounds, { Feel.chooseSounds(it) }, modifier = Modifier.label("Sounds"))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.ui_animate_screens_lists_and_the_sc), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(Feel.motion, { Feel.chooseMotion(it) }, modifier = Modifier.label("Motion"))
        }
        Text(stringResource(R.string.ui_sound_profile), style = MaterialTheme.typography.labelLarge)
        SoundProfile.entries.forEach { profile ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    Feel.chooseSoundProfile(profile)
                    Feel.cue(view, Cue.GOAL, force = true)
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.RadioButton(selected = Feel.soundProfile == profile, onClick = {
                    Feel.chooseSoundProfile(profile)
                    Feel.cue(view, Cue.GOAL, force = true)
                })
                Column {
                    Text(profile.label, style = MaterialTheme.typography.bodyLarge)
                    Text(profile.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        FlowRowOfPreviews(view)
        if (Feel.reducedMotion(context)) {
            Text(
                "Your phone has animations turned off, so the pop and the reward bounce are skipped.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowOfPreviews(view: android.view.View) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Task" to Cue.TASK, "Habit" to Cue.HABIT, "Undo" to Cue.UNDO, "Goal" to Cue.GOAL, "Treat" to Cue.TREAT).forEach { (name, cue) ->
            OutlinedButton(onClick = { Feel.cue(view, cue, force = true) }) { Text("Preview $name") }
        }
    }
}

private class Release(val version: String, val notes: List<String>)

/** Newest first. Add one entry per release. */
private val changelog = listOf(
    Release(
        "0.11.2",
        listOf(
            "A goal page lists only its milestones. Tasks are no longer linked to goals from the app; ones you linked before stay in your Tasks tab.",
            "Habits are no longer called goals: their own pages, buttons, titles and a Habits list on Today.",
            "Rewards: a Stock of treats you've won but not used yet, with a count and a Use one button. A treat can be one time only: once won, it leaves the jar.",
            "A milestone shows its goal's name outside the goal, such as \"Read: 100\", so two milestones called 100 can be told apart (monsters, calendar, Today).",
        ),
    ),
    Release(
        "0.11.1",
        listOf(
            "A goal page now shows tasks and milestones in one list. Milestones carry a flag.",
            "Calendar: a long \"Anytime\" list shows a few items with Show more, so the day's timeline stays visible.",
        ),
    ),
    Release(
        "0.11.0",
        listOf(
            "Holiday mode pauses every goal for the days you choose. Streaks wait for you.",
            "Day notes with a mood, private to you, and mood lines in Insights once you have enough notes.",
            "Weekly review: look back, clear overdue tasks, and pick up to three things to focus on.",
            "Routines: a list of goals ticked together, such as a morning routine.",
            "Tasks: select several and change their due date or priority at once.",
            "Share text from another app to start a new task. New app shortcuts for New task and Today.",
            "Fixed: the Undo button on the bottom message now shows up.",
        ),
    ),
    Release(
        "0.10.0",
        listOf(
            "Daily quest on Today: a small target that pays bonus XP. Missing it costs nothing. Titan days, Blaze streaks, bosses and monsters also pay bonus XP.",
            "Focus timer for tasks and goals, with a countdown in the notification and a history of sessions.",
            "History calendar with day details, records and Blaze streaks. Insights for any week or month, with a button to share them.",
            "Calendar: event end dates, reminders, skipping one day of a repeat, month overview, drag to move, and suggested times for plans without one.",
            "Tasks: quick-add times like \"Essay tomorrow 3pm 1h\", templates, custom repeat, editable steps, and grouping by tag.",
            "Quiet hours, a sound switch and a Snooze button on reminders. New Score and Agenda widgets.",
            "Export tasks to CSV, export everything to JSON, import tasks from CSV, and share a backup.",
            "Seven new badges. Content stays centered on tablets and in landscape.",
        ),
    ),
    Release(
        "0.9.0",
        listOf(
            "New Calendar tab for time blocking: a week strip and the day hour by hour, with events, tasks and goals as blocks.",
            "Events: things that are not tasks, with a start time, a length and an optional repeat. They never change your score or XP.",
            "Tasks can have a start time and a length. Tasks and goals without a time show as 30-minute blocks in an Anytime strip. Tap a block to set its time.",
            "Stats and Rewards moved into a new More tab, to make room for the calendar.",
        ),
    ),
    Release(
        "0.8.0",
        listOf(
            "Hero levels: your day-score points become XP that never goes down. Level up from Rookie to Legend.",
            "Weekly boss: a new boss every Monday. Your points hurt it, and beating it wins a treat. Past bosses are listed on the Monsters screen.",
            "The day score now shows in the Today widget and in the evening check-in.",
            "More reliable reminders: allow exact alarms and skip battery restriction in Settings. No permanent notification needed.",
        ),
    ),
    Release(
        "0.7.1",
        listOf(
            "Monsters: name a struggle and fight it with linked goals, habits and tasks. Beat it to win treats; it comes back stronger.",
            "Permanent kill: turn a monster into a final boss, 5 times stronger, that never returns.",
            "Find Monsters on the Rewards tab. Monster names are hidden outside the Monsters screens unless you turn that off.",
        ),
    ),
    Release(
        "0.7.0",
        listOf(
            "A day score on Today: points done out of points planned, with a Spark, Blaze or Titan level.",
            "A softer, rounder look with animations for screens, lists and the score, and a Motion switch.",
            "Sound profiles: Soft chimes, Pop and bubble, Retro game and Wood and click.",
            "A Habits tab with a day grid, hide finished, and timeline separators.",
        ),
    ),
    Release(
        "0.6.0",
        listOf(
            "A pop when you tick, optional haptics and sounds, and a bigger moment when a target is met.",
            "A Black (OLED) theme and four more accent colors: Rose, Teal, Amber and Graphite.",
            "Settings are in folding groups, and this What's new list lives under Help.",
            "A short intro on first run, sample data you can clear in one tap, and goal bundles.",
            "Pick an emoji icon for any goal or task.",
        ),
    ),
    Release("0.5.2", listOf("Small details.")),
    Release("0.5.1", listOf("Milestones also appear as tasks.")),
    Release("0.5.0", listOf("Tasks linked to goals, visible rewards, and clearer stats.")),
    Release("0.4.0", listOf("Tasks, and clearer screens.")),
    Release("0.3.0", listOf("Reminders, and a counter for numeric goals.")),
)

/** Help, sample data and the version row that opens "What's new". */
@Composable
fun HelpGroup(controller: AppController) {
    val context = LocalContext.current
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
    }
    var showNews by remember { mutableStateOf(false) }
    val hasSample = remember(controller.version) { controller.read(false) { hasSampleData() } }
    SettingsGroup("Help and about", "help") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { Feel.introRequested = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_show_intro_again)) }
            if (hasSample) {
                OutlinedButton(onClick = {
                    if (controller.act { clearSampleData() }) controller.message = "Sample data cleared."
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_clear_sample_data)) }
            } else {
                OutlinedButton(onClick = { controller.act { createSampleData(controller.today) } }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ui_try_it_with_sample_data))
                }
            }
            Row(
                Modifier.fillMaxWidth().clickable(onClickLabel = "Open what's new") { showNews = true }.padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.ui_version))
                Text("$version  (What's new)", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    if (showNews) {
        InfoSheet("What's new", onDismiss = { showNews = false }) {
            changelog.forEach { release ->
                Text(release.version, style = MaterialTheme.typography.titleMedium)
                release.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
