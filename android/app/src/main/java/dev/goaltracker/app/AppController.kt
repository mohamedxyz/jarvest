package dev.goaltracker.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.time.LocalDate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.goaltracker.core.BulkEdit
import dev.goaltracker.core.CoreException
import dev.goaltracker.core.GoalCore
import dev.goaltracker.core.LogResult
import dev.goaltracker.core.Priority
import dev.goaltracker.core.Task
import dev.goaltracker.core.TaskEdit
import dev.goaltracker.core.TaskFilter
import dev.goaltracker.core.TaskSort
import dev.goaltracker.core.RewardDraw

/**
 * The bridge between screens and the Rust core. Screens read through [read] and
 * change data through [act] or [log]. Each change bumps [version], and screens
 * reload whatever depends on it.
 */
/** The due date a new task starts with. */
enum class DefaultDue(val label: String) { NONE("No date"), TODAY("Today"), TOMORROW("Tomorrow") }

class UndoOffer(val text: String, val action: () -> Unit)

class AppController(private val app: GoalTrackerApp) {
    val core: GoalCore get() = app.core

    var version by mutableIntStateOf(0)
        private set
    var today by mutableStateOf(todayString())
        private set
    var message by mutableStateOf<String?>(null)
    var pendingDraw by mutableStateOf<RewardDraw?>(null)
        private set
    var showJarPrompt by mutableStateOf(false)

    private val prefs = app.getSharedPreferences("ui", Context.MODE_PRIVATE)

    // The theme lives in the database (the `meta` table), so a restored backup
    // brings the look back too. An old "accent" or "black" value is ignored. Older versions kept them in SharedPreferences, so a value
    // found there is copied into the database once.
    var themeMode by mutableStateOf(loadLook("theme", ThemeMode.DARK))
        private set

    private inline fun <reified E : Enum<E>> loadLook(key: String, default: E): E {
        val stored = try {
            core.getPref(key) ?: prefs.getString(key, null)?.also { core.setPref(key, it) }
        } catch (_: CoreException) {
            null
        }
        return enumValues<E>().firstOrNull { it.name == stored } ?: default
    }

    /** Re-reads the look after a restore replaced the database. */
    private fun reloadLook() {
        themeMode = loadLook("theme", ThemeMode.DARK)
    }

    fun chooseTheme(mode: ThemeMode) {
        themeMode = mode
        try {
            core.setPref("theme", mode.name)
        } catch (e: CoreException) {
            message = e.userMessage()
        }
    }

    /** Tip cards the user has closed. Kept on the phone only, so backups don't carry it. */
    var dismissedTips by mutableStateOf(prefs.getStringSet("dismissed_tips", emptySet()) ?: emptySet())
        private set

    fun isTipDismissed(id: String) = id in dismissedTips

    fun dismissTip(id: String) {
        dismissedTips = dismissedTips + id
        prefs.edit().putStringSet("dismissed_tips", dismissedTips).apply()
    }

    fun resetTips() {
        dismissedTips = emptySet()
        prefs.edit().remove("dismissed_tips").apply()
    }

    // The Tasks tab opens the way the user left it, and new tasks start with their own defaults.
    var taskFilter by mutableStateOf(readEnum("task_filter", TaskFilter.ALL))
        private set
    var taskSort by mutableStateOf(readEnum("task_sort", TaskSort.DUE))
        private set
    var defaultPriority by mutableStateOf(readEnum("default_priority", Priority.MED))
        private set
    var defaultDue by mutableStateOf(readEnum("default_due", DefaultDue.NONE))
        private set

    fun chooseTaskFilter(value: TaskFilter) {
        taskFilter = value
        prefs.edit().putString("task_filter", value.name).apply()
    }

    fun chooseTaskSort(value: TaskSort) {
        taskSort = value
        prefs.edit().putString("task_sort", value.name).apply()
    }

    fun chooseDefaultPriority(value: Priority) {
        defaultPriority = value
        prefs.edit().putString("default_priority", value.name).apply()
    }

    fun chooseDefaultDue(value: DefaultDue) {
        defaultDue = value
        prefs.edit().putString("default_due", value.name).apply()
    }

    // Task list look and behaviour (Settings > Tasks). Kept on the phone only.
    var showTaskNotes by mutableStateOf(prefs.getBoolean("task_notes", false))
        private set
    var showChecklistProgress by mutableStateOf(prefs.getBoolean("task_progress", true))
        private set
    var compactTaskRows by mutableStateOf(prefs.getBoolean("task_compact", false))
        private set
    /** Monster names can be sensitive, so outside the Monsters screens they show as "a monster". */
    var hideMonsterNames by mutableStateOf(prefs.getBoolean("hide_monster_names", true))
        private set
    fun chooseHideMonsterNames(on: Boolean) { hideMonsterNames = on; prefs.edit().putBoolean("hide_monster_names", on).apply() }
    /** The monster that was just beaten, until the person closes the dialog. */
    var monsterVictory by mutableStateOf<dev.goaltracker.core.MonsterEvent?>(null)

    /** Records beaten monsters and starts their next rounds. Each win is returned once. */
    fun checkMonsters() {
        val events = try { core.monsterTick(today) } catch (_: CoreException) { emptyList() }
        if (events.isNotEmpty()) {
            monsterVictory = events.first()
            Feel.celebrate(null, treat = events.any { it.prizes > 0u })
        }
        checkProgress()
    }

    /** The day shown on the Calendar tab (YYYY-MM-DD). It starts on today and is not saved. */
    var calendarDate by mutableStateOf(todayString())

    /** The weekly boss that was just beaten, until the person closes the dialog. */
    var bossVictory by mutableStateOf<dev.goaltracker.core.BossEvent?>(null)

    /**
     * Saves today's points (XP), spawns this week's boss and records bosses that fell. Also tells the
     * person when they reach a new hero level. Each boss win is returned once.
     */
    private fun checkProgress() {
        val events = try { core.progressTick(today) } catch (_: CoreException) { emptyList() }
        if (events.isNotEmpty() && bossVictory == null) {
            bossVictory = events.first()
            Feel.celebrate(null, treat = events.any { it.prizes > 0u })
        }
        val hero = try { core.hero() } catch (_: CoreException) { return }
        val seen = prefs.getInt("hero_level_seen", 0)
        // The first run only remembers the level, so nobody gets a message for old progress.
        if (seen != 0 && hero.level.toInt() > seen && message == null) {
            message = "Level up! You are now level ${hero.level}, ${hero.title}."
        }
        if (hero.level.toInt() != seen) prefs.edit().putInt("hero_level_seen", hero.level.toInt()).apply()
    }
    // "Hide finished" switches, one per screen. They only hide things in the list, never delete.
    var hideDoneTasks by mutableStateOf(prefs.getBoolean("hide_done_tasks", false))
    var hideDoneGoals by mutableStateOf(prefs.getBoolean("hide_done_goals", false))
    var hideDoneHabits by mutableStateOf(prefs.getBoolean("hide_done_habits", false))
    var hideDoneToday by mutableStateOf(prefs.getBoolean("hide_done_today", false))
    fun chooseHideDoneTasks(on: Boolean) { hideDoneTasks = on; prefs.edit().putBoolean("hide_done_tasks", on).apply() }
    fun chooseHideDoneGoals(on: Boolean) { hideDoneGoals = on; prefs.edit().putBoolean("hide_done_goals", on).apply() }
    fun chooseHideDoneHabits(on: Boolean) { hideDoneHabits = on; prefs.edit().putBoolean("hide_done_habits", on).apply() }
    fun chooseHideDoneToday(on: Boolean) { hideDoneToday = on; prefs.edit().putBoolean("hide_done_today", on).apply() }
    var groupTasksByTag by mutableStateOf(prefs.getBoolean("task_group_tags", false))
        private set
    /** Ask before moving a task to the trash. Off by default, because Undo is always offered. */
    var confirmTaskTrash by mutableStateOf(prefs.getBoolean("confirm_task_trash", false))
        private set
    /** Ask before deleting for good, which can't be undone. On by default. */
    var confirmDeleteForever by mutableStateOf(prefs.getBoolean("confirm_delete_forever", true))
        private set
    /** Archive finished tasks after this many days, or -1 for never. */
    var archiveAfterDays by mutableStateOf(prefs.getInt("archive_after_days", -1))
        private set

    fun chooseShowTaskNotes(on: Boolean) { showTaskNotes = on; prefs.edit().putBoolean("task_notes", on).apply() }
    fun chooseShowChecklistProgress(on: Boolean) { showChecklistProgress = on; prefs.edit().putBoolean("task_progress", on).apply() }
    fun chooseCompactTaskRows(on: Boolean) { compactTaskRows = on; prefs.edit().putBoolean("task_compact", on).apply() }
    fun chooseGroupTasksByTag(on: Boolean) { groupTasksByTag = on; prefs.edit().putBoolean("task_group_tags", on).apply() }
    fun chooseConfirmTaskTrash(on: Boolean) { confirmTaskTrash = on; prefs.edit().putBoolean("confirm_task_trash", on).apply() }
    fun chooseConfirmDeleteForever(on: Boolean) { confirmDeleteForever = on; prefs.edit().putBoolean("confirm_delete_forever", on).apply() }

    fun chooseArchiveAfterDays(days: Int) {
        archiveAfterDays = days
        prefs.edit().putInt("archive_after_days", days).apply()
        runAutoArchive()
    }

    /** Archives old finished tasks if the setting is on. Called when the app opens or the setting changes. */
    fun runAutoArchive() {
        if (archiveAfterDays < 0) return
        val moved = read(0u) { autoArchiveTasks(archiveAfterDays.toUInt(), today) }
        if (moved > 0u) version++
    }

    /** The due date a new task starts with, as set in Settings. */
    fun defaultDueDate(): String? = when (defaultDue) {
        DefaultDue.NONE -> null
        DefaultDue.TODAY -> today
        DefaultDue.TOMORROW -> LocalDate.parse(today).plusDays(1).toString()
    }

    // Backups. The date is kept on the phone, outside the database, so restoring an old
    // backup can't make a stale date look fresh.
    var lastBackup by mutableStateOf(prefs.getString("last_backup", null))
        private set
    var backupSnoozedUntil by mutableStateOf(prefs.getString("backup_snoozed_until", null))
        private set

    fun markBackup() {
        lastBackup = today
        prefs.edit().putString("last_backup", today).apply()
        Reminders.updateStaleBackupAlarm(app, today, today)
    }

    /** Picks up a backup the automatic worker made while the app was closed. */
    fun reloadBackupDate() {
        lastBackup = prefs.getString("last_backup", null)
        Reminders.updateStaleBackupAlarm(app, lastBackup, today)
    }

    fun snoozeBackup(urgent: Boolean) {
        val until = LocalDate.parse(today).plusDays(if (urgent) 1 else 3).toString()
        backupSnoozedUntil = until
        prefs.edit().putString("backup_snoozed_until", until).apply()
    }

    /** 0 = fine or snoozed, 1 = a week old, 2 = a month old, or never saved with a lot of data. */
    fun backupLevel(): Int {
        val snoozed = backupSnoozedUntil?.let { today <= it } ?: false
        val items = read(0) { listGoals(false).size + listTasks(TaskFilter.ALL, TaskSort.DUE, today).size }
        if (snoozed || items == 0) return 0
        val last = lastBackup ?: return if (items >= 5) 2 else 1
        val days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(last), LocalDate.parse(today))
        return when {
            days > 30 -> 2
            days > 7 -> 1
            else -> 0
        }
    }

    private inline fun <reified E : Enum<E>> readEnum(key: String, default: E): E =
        enumValues<E>().firstOrNull { it.name == prefs.getString(key, null) } ?: default

    /** Counts typed in on numeric goals but not submitted yet, by goal ID. */
    /** Text shared from another app, waiting to fill the quick-add box on the Tasks tab. */
    var pendingQuickAdd: String? = null

    fun takePendingQuickAdd(): String? = pendingQuickAdd.also { pendingQuickAdd = null }

    val counters = mutableStateMapOf<String, Int>()

    /** Logs a goal's counter as one entry, then resets it. Returns whether it worked. */
    fun submitCounter(goalId: String): Boolean {
        val count = counters[goalId] ?: 0
        if (count <= 0) return false
        val ok = log { logAmountAt(goalId, count.toDouble(), todayString(), nowMinute()) }
        if (ok) counters[goalId] = 0
        return ok
    }

    // One prompt per app session is enough of a nudge.
    private var jarPromptUsed = false

    fun <T> read(fallback: T, block: GoalCore.() -> T): T = try {
        core.block()
    } catch (e: CoreException) {
        // Reads run during composition, so the message is shown on the next frame.
        Handler(Looper.getMainLooper()).post { message = e.userMessage() }
        fallback
    }

    /** Runs a change and returns whether it succeeded. */
    fun act(block: GoalCore.() -> Unit): Boolean = try {
        core.block()
        refresh()
        true
    } catch (e: CoreException) {
        message = e.userMessage()
        false
    }

    /** Like [act], for a change that returns a value. Returns null if it failed. */
    fun <T> change(block: GoalCore.() -> T): T? = try {
        val result = core.block()
        refresh()
        result
    } catch (e: CoreException) {
        message = e.userMessage()
        null
    }

    /** A message with an Undo button, such as after moving a task to the trash. */
    var undo by mutableStateOf<UndoOffer?>(null)

    /**
     * Ticks or unticks a task and always offers Undo, so a stray tap is easy to fix.
     * Finishing a repeating task also says when the next one is due.
     */
    fun toggleTaskWithUndo(taskId: String, title: String) {
        val result = change { toggleTask(taskId, today) } ?: return
        // A task that mirrors a milestone can win or lose a treat, like the milestone itself.
        reportReward(result.reward)
        val text = when {
            !result.done -> "\"$title\" is open again."
            result.nextDue != null -> "\"$title\" done. Next one is due ${formatDate(result.nextDue!!)}."
            else -> "\"$title\" done."
        }
        undo = UndoOffer(text) { change { toggleTask(taskId, today) } }
    }

    /**
     * Applies one change to many tasks and offers a single Undo that puts the whole batch back.
     * [before] are the tasks as they were, so Undo knows what to restore.
     */
    fun bulkTasks(before: List<Task>, edit: BulkEdit, verb: String) {
        val result = change { bulkUpdateTasks(before.map { it.id }, edit, today) } ?: return
        reportReward(result.reward)
        // Milestone tasks take every change except moving to another goal; the user is told.
        val skipped = if (result.skippedMilestones > 0u) {
            " ${countOf(result.skippedMilestones.toInt(), "milestone")} kept with its goal."
        } else ""
        if (result.changed == 0u) {
            message = "Nothing changed.$skipped".trim()
            return
        }
        val changed = before.filter { it.milestoneId == null || !edit.changeGoal }
        // "{n}" in the verb marks where the count goes: "Moved {n} to today" reads "Moved 3 tasks to today."
        val count = countOf(result.changed.toInt(), "task")
        val sentence = if ("{n}" in verb) verb.replace("{n}", count) else "$verb $count"
        undo = UndoOffer("$sentence.$skipped") {
            act {
                changed.forEach { old ->
                    when {
                        edit.done -> if (!old.done) toggleTask(old.id, today)
                        edit.trash -> restoreTask(old.id)
                        edit.archive -> if (old.archivedDate == null) unarchiveTask(old.id)
                        else -> {
                            updateTask(
                                old.id,
                                TaskEdit(old.title, old.notes, old.due, old.priority, old.tag, old.repeat, old.goalId),
                            )
                            // Clearing a day also cleared its start time, so put that back too.
                            if (edit.changeDue && old.due != null && old.dueTime != null) {
                                setTaskTime(old.id, old.dueTime, old.duration)
                            }
                        }
                    }
                }
            }
        }
    }

    /** Runs a logging action, then checks whether it earned a treat. */
    fun log(block: GoalCore.() -> LogResult): Boolean = try {
        val result = core.block()
        reportReward(result)
        refresh()
        true
    } catch (e: CoreException) {
        message = e.userMessage()
        false
    }

    /** Prompts for a treat, or says what happened to the reward, so a missing treat never looks like a bug. */
    private fun reportReward(result: LogResult) {
        if (result.jarEmpty && !jarPromptUsed) {
            jarPromptUsed = true
            showJarPrompt = true
        }
        // A target was met (it rolled for a treat, won one, or found the jar empty): celebrate.
        if (result.drew > 0u || result.noTreat > 0u || result.jarEmpty) Feel.celebrate(null, treat = result.drew > 0u)
        if (result.treatsRemoved > 0u) {
            message = "Unticked. The treat that target won was removed too."
        } else if (result.noTreat > 0u) {
            val left = core.rewardProgress().guaranteedIn.toInt()
            message = "Target met. No treat this time. " +
                if (left <= 1) "Your next target is guaranteed to win one."
                else "A treat is guaranteed within $left more targets."
        }
    }

    /** Called when the app returns to the front, since the widget or date may have changed. */
    fun onResume() {
        today = todayString()
        runAutoArchive()
        checkMonsters()
        version++
        loadPendingDraw()
    }

    fun refresh() {
        // Edits can add, move, or remove reminders, so the alarms are rebuilt each time.
        Reminders.rescheduleAll(app)
        reloadLook()
        reloadBackupDate()
        checkMonsters()
        version++
        loadPendingDraw()
        app.refreshWidgets()
    }

    fun dismissDraw() {
        val draw = pendingDraw ?: return
        pendingDraw = null
        read(Unit) { markDrawSeen(draw.id) }
        version++
        loadPendingDraw()
    }

    /** First day of the week set in Settings: 1 = Monday, 6 = Saturday, 7 = Sunday. */
    fun weekStart(): Int = read(1) { weekStart().toInt() }

    fun chooseWeekStart(day: Int) {
        act { setWeekStart(day.toUByte()) }
    }

    private fun loadPendingDraw() {
        pendingDraw = read(emptyList()) { unseenDraws() }.firstOrNull()
    }
}

fun CoreException.userMessage(): String = when (this) {
    is CoreException.Invalid -> reason
    is CoreException.InvalidBackup -> "That file can't be restored: $reason."
    is CoreException.Database -> {
        Log.e("GoalTracker", detail)
        "Something went wrong with your data. Please try again."
    }
}
