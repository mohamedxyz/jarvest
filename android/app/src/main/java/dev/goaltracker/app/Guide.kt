package dev.goaltracker.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The in-app guide: short articles on what each part of Jarvest does and how to use it well.
 * Open it from Settings, or from the "?" in the top bar of a screen, which jumps to that screen's
 * article. Plain text lives here, not in the core: it describes the app, it is not data.
 *
 * Article text: each string is a paragraph. One that starts with "• " is a bullet, and one that
 * starts with "Tip: " is shown as a highlighted tip.
 */
class GuideArticle(
    val id: String,
    val section: String,
    val title: String,
    val summary: String,
    val body: List<String>,
    val related: List<String> = emptyList(),
)

internal val guideArticles: List<GuideArticle> = listOf(
    // ---- Start ----
    GuideArticle(
        "start", "Start here", "Start here: set up in five minutes",
        "The quickest way to a setup that works.",
        listOf(
            "Jarvest keeps your goals, habits and tasks on this phone and turns finishing them into a game: points, levels, a weekly boss and treats you choose yourself.",
            "• 1. Add two or three habits, not ten. Habits are things you repeat, such as Read or Walk. Quests > Habits > New habit.",
            "• 2. Add one goal with milestones, such as \"Read 12 books\" with milestones 3, 6, 9, 12. Quests > Goals > New goal.",
            "• 3. Put a few treats in the jar: small things you enjoy, such as a coffee out or an episode of a show. Hero > Rewards.",
            "• 4. Add today's tasks in one line each, for example \"Call bank tomorrow 10am !high\". Plan > Tasks.",
            "• 5. Turn on automatic backup in Settings > Backup and data, so a lost phone does not mean lost history.",
            "Then use Today every day. It lists everything planned for today; tick things as you do them.",
            "Tip: Start smaller than you think. A habit you do 3 times a week for a year beats a daily one you drop in week two. You can always add more later.",
        ),
        listOf("today", "kinds", "treats", "quickadd", "backup"),
    ),
    GuideArticle(
        "today", "Start here", "The Today screen",
        "Your plan for today and the fastest way to log.",
        listOf(
            "Today shows every habit and goal planned for today and every task due today or overdue. Tick a card to log it; tick again to undo.",
            "A card stays after you tick it, so a mistake can be undone right there. Cards keep their order so they do not jump while you tick.",
            "At the top are the day score, your level and XP, and the daily quest.",
            "• Number goals have a counter: use + and −, then Submit, or type an exact amount.",
            "• A habit only shows on its planned weekdays. Skipped or paused days hide it.",
            "• Open a card to see its page with stats and history.",
            "Tip: Open Today once in the morning to see the plan, and once in the evening to tick what you did. The evening check-in reminder (Settings) can nudge you.",
        ),
        listOf("dayscore", "quest", "kinds"),
    ),
    GuideArticle(
        "dayscore", "Start here", "Day score and day levels",
        "How a day is scored: Spark, Blaze and Titan.",
        listOf(
            "Everything planned today is worth points, and finishing it earns them.",
            "• Each goal or habit on today's list: 10 points.",
            "• Each task due today or overdue, or finished today: 5 (low), 10 (medium) or 15 (high) points.",
            "• Milestone tasks are not counted twice: their goal already counts.",
            "The percent of points earned gives the day a level: Spark from 1% to 49%, Blaze from 50% to 99%, Titan at 100%.",
            "Points earned become XP, so the day score feeds your hero level and the weekly boss.",
            "Tip: Don't overload a day. A small plan you finish (Titan) is worth more XP bonus and feels better than a huge plan at 40%. Move tasks you won't do to another day instead of letting them sit overdue.",
        ),
        listOf("xp", "tasks"),
    ),

    // ---- Goals and habits ----
    GuideArticle(
        "kinds", "Goals and habits", "Goals vs habits: which to use",
        "Yes/no goals, number goals and habits, and when to pick each.",
        listOf(
            "Habits are things you repeat for good. Goals are things you finish. Pick the type when you create it; it can't be changed later, because that would rewrite your history.",
            "• Habit: at most one check-in per day, with a target per day, week or month, such as 3 times a week. Use it for Gym, Read, Meditate.",
            "• Yes/no goal: done once, with optional milestones and a deadline. Use it for Pass the exam or Move house.",
            "• Number goal: you add amounts, several times a day if you like, toward a target per period (8 glasses a day) and/or a total (100 km). Its page shows this period's result, such as 3 of 8 glasses today, and the total so far.",
            "• Quit habit: something to avoid. You log slips, not successes.",
            "Title, notes, category, unit, icon and deadline can be edited any time. Type, period and targets can't.",
            "Tip: Prefer weekly targets for habits (3 per week) over daily ones. A missed day then doesn't break anything, and streaks count weeks, which is kinder and more honest.",
        ),
        listOf("habits", "milestones", "quit"),
    ),
    GuideArticle(
        "habits", "Goals and habits", "Habits, streaks and the habit grid",
        "How streaks are counted and how to keep them alive.",
        listOf(
            "The Habits tab shows a grid of the last days. Tap a cell to tick or untick that day; use the arrows to go back further.",
            "A streak is the number of periods in a row where the target was met. For a weekly habit, that's weeks.",
            "• The current period never breaks a streak while it is still running.",
            "• The period you created a habit in only counts if you met it, so starting late in a week costs nothing.",
            "• The completion rate covers the last 12 periods.",
            "• Weekdays: a habit can be planned only on some days (Mon, Wed, Fri). Other days don't show and don't count.",
            "• Log a past day from the habit's page if you forgot to tick it.",
            "Tip: Use Skip today for a real reason (sick, travelling) rather than letting the streak break. Skipped days don't count against you.",
        ),
        listOf("skips", "stats", "widgets"),
    ),
    GuideArticle(
        "milestones", "Goals and habits", "Milestones",
        "Break a goal into steps that each count as a win.",
        listOf(
            "A goal can have ordered milestones. Each one appears in Tasks as \"Goal: milestone\", so two goals that both have a milestone called 100 are easy to tell apart.",
            "• Ticking a milestone in Tasks or on the goal page ticks both.",
            "• Each milestone is a target, so ticking it can win a treat.",
            "• In Tasks a milestone works like any task: give it a date, time or priority, move it on the calendar, select several and change them together, archive it.",
            "• Moving a milestone's task to the trash hides the milestone from its goal too. Restore it within 30 days to bring it back; emptying the trash deletes it for good.",
            "• A milestone always stays with its goal and can't repeat.",
            "• Archive or delete a goal and its milestones leave Tasks and the calendar too. Restore the goal and they come back.",
            "Tip: Make milestones you can reach in a week or two. Five small wins along the way keep you going much better than one big finish line.",
        ),
        listOf("kinds", "treats", "archive"),
    ),
    GuideArticle(
        "quit", "Goals and habits", "Quit habits",
        "For things you want to stop doing.",
        listOf(
            "A quit habit is for something to avoid, such as smoking or doom-scrolling. Instead of ticking successes, you log a slip when it happens. A day with no slip is a clean day.",
            "• On Today a quit habit counts as done while there is no slip today.",
            "• Linked to a monster, each clean day damages it, and a slip heals it a little. A slip never takes away past progress or trophies.",
            "• The goal page shows when slips usually happen once there are enough logs.",
            "Tip: Be honest with slips. The point is to see the pattern (the time of day, the weekday) so you can plan around it, not to punish yourself.",
        ),
        listOf("monsters", "stats"),
    ),
    GuideArticle(
        "skips", "Goals and habits", "Skip, pause and holiday mode",
        "Take a break without losing streaks.",
        listOf(
            "• Skip today: from a goal page. Today doesn't count for that goal.",
            "• Pause days: from a goal page. Pick a range of days to skip for one goal.",
            "• Holiday mode: Hero > Holiday. Pauses every goal for a stretch of days in one step. Ending it removes only the skips it added; skips you made by hand stay.",
            "Skipped days neither count toward nor break streaks and rates. Tasks are not moved by holiday mode and still count in the day score.",
            "Tip: Start holiday mode before you leave, not after. Goals created during a holiday are not covered.",
        ),
        listOf("habits"),
    ),
    GuideArticle(
        "routines", "Goals and habits", "Routines",
        "Do several habits in one go, in order.",
        listOf(
            "A routine is an ordered list of habits and goals done together, such as a morning routine: Water, Stretch, Journal. Hero > Routines.",
            "Run a routine to tick its steps one after another. Each step is logged exactly like ticking it on Today, so a routine gives nothing extra, and you can't double count.",
            "Quit habits, archived and deleted goals can't be steps.",
            "Tip: Attach a routine to something you already do every day, such as after coffee. The anchor does most of the work.",
        ),
        listOf("habits"),
    ),
    GuideArticle(
        "archive", "Goals and habits", "Archive, trash and reordering",
        "Keep the lists short without losing history.",
        listOf(
            "• Archive a finished goal to hide it while keeping its history. See archived ones under Quests > Filter > Archived, and restore them from there.",
            "• Delete moves a goal to the trash for 30 days. Restore it within that time and nothing is lost; after that it is erased.",
            "• Reorder: Quests > Reorder, then drag.",
            "• Filter and search: find goals by name or category, and hide ones already done.",
            "An archived or deleted goal can't be logged, and its milestones are hidden everywhere until you restore it.",
            "Tip: Archive, don't delete, goals you finished. Their history still counts in Year in review and your records.",
        ),
        listOf("milestones"),
    ),
    GuideArticle(
        "stats", "Goals and habits", "Stats and the goal page",
        "Read the numbers that actually help.",
        listOf(
            "A goal page shows the current and best streak, the rate met, a progress chart (days, weeks or months; last 12, 26 or 52), the activity heatmap, your best and hardest weekday and your usual time of day.",
            "Hero > Stats shows all goals together, with direction (getting better or worse) over recent periods.",
            "• Best and hardest weekday appears once each weekday has enough days.",
            "• Usual time of day appears after 5 logs made on the day itself.",
            "Tip: Look at your hardest weekday and change the plan for it, for example a smaller target on Mondays, instead of trying harder every week.",
        ),
        listOf("history", "habits"),
    ),

    // ---- Tasks and calendar ----
    GuideArticle(
        "tasks", "Tasks and calendar", "Tasks",
        "One-off to-dos, separate from goals.",
        listOf(
            "Tasks are things to get done once (or on a repeat), such as Pay rent. Plan > Tasks.",
            "• Filters: All, Today, Overdue, Done. Sort by due date, priority or A to Z.",
            "• A task can have a due date, start time, length, priority, tag, notes, steps and a repeat.",
            "• A task can be linked to a goal for context. It never logs the goal or counts in its stats.",
            "• Select several tasks (long-press) to mark them done, move them, change priority, archive or trash them in one step.",
            "• Archive keeps finished tasks out of the way; Trash keeps deleted ones for 30 days.",
            "Tip: Give a due date only to tasks that really have one. A list of 30 \"due today\" tasks just teaches you to ignore it.",
        ),
        listOf("quickadd", "templates", "calendar"),
    ),
    GuideArticle(
        "quickadd", "Tasks and calendar", "Quick add: a task in one line",
        "Type the date, time, priority and tag with the title.",
        listOf(
            "Type a line in the box at the top of Tasks. Words are read out of the line; the rest is the title.",
            "• Day: today, tomorrow (tmr), a weekday (fri, on friday), next monday, in 3 days, in 2 weeks, or a date such as 12 dec or dec 12.",
            "• Time: 3pm, 3:30pm, 11am, 15:30 or at 3pm.",
            "• Length: 45m, 45min, 2h or 1.5h.",
            "• Priority: !high, !med, !low (or !h, !m, !l, !3, !2, !1).",
            "• Tag: #uni, #home.",
            "Example: \"Essay friday 3pm 2h !high #uni\". Each part must be its own word. English only.",
            "You can also share text from another app to Jarvest to add it as a task.",
            "Tip: Settings > Tasks sets what a new task starts with, so you only type what's different.",
        ),
        listOf("tasks"),
    ),
    GuideArticle(
        "templates", "Tasks and calendar", "Templates, tags and search",
        "Reuse task setups and find things fast.",
        listOf(
            "• Templates: save a task with its steps (such as a packing list) and add it again in one tap. Tasks > Templates.",
            "• Tags group tasks; tap a tag to see only those.",
            "• Search finds tasks by title, notes and tags.",
            "• Export and import tasks as CSV from Settings > Backup and data.",
            "Tip: Make a template for anything you do the same way more than twice: weekly shop, travel prep, monthly bills.",
        ),
        listOf("tasks"),
    ),
    GuideArticle(
        "calendar", "Tasks and calendar", "Calendar and time blocking",
        "See the day hour by hour and give things a time.",
        listOf(
            "Plan > Calendar shows events, tasks due that day and goals planned that day. Anything without a start time sits in the Anytime row.",
            "• Tap an empty spot to add an event or task there, or to schedule something from Anytime.",
            "• Press, hold and drag on empty space to draw a time range.",
            "• Press and hold a block to move it; drag its bottom edge to change its length; drop it on a day in the week strip to change the day.",
            "• Pinch to zoom. Every change offers Undo.",
            "• Events can repeat daily or weekly, have an end date and a reminder, and skip one day.",
            "• Suggest times (in the menu) proposes slots for plans without one; nothing changes until you apply.",
            "The calendar only plans: it never changes score, XP, streaks or rewards.",
            "Tip: Block time for your hardest task first, in your best hours. Leave gaps; a calendar packed edge to edge always slips.",
        ),
        listOf("tasks", "focus"),
    ),
    GuideArticle(
        "focus", "Tasks and calendar", "Focus timer",
        "Work on one thing with a countdown.",
        listOf(
            "Start a focus session for a task or goal. A countdown runs in a notification, and the time is saved in your history.",
            "Focus time is recorded only. It doesn't earn points or treats, so the score stays about what you finished.",
            "Tip: 25 to 50 minutes is a good length. Put the phone face down and decide the very first step before you start.",
        ),
        listOf("history"),
    ),

    // ---- Rewards and the game ----
    GuideArticle(
        "treats", "Rewards and the game", "Treats and the jar",
        "How meeting targets can win something you chose.",
        listOf(
            "Fill the jar with treats you'd enjoy (Hero > Rewards). Every target you meet has a chance to draw one.",
            "A target is a period met (such as this week's 3 workouts), a total reached, a yes/no goal ticked or a milestone ticked. Each pays out at most once.",
            "• Odds are 1 in 5.",
            "• Comeback: after a missed period, meeting the next one doubles the odds to 2 in 5.",
            "• Pity: a treat is guaranteed by the 10th target since the last one.",
            "• Rolls are fixed in advance, so undoing and logging again can't re-roll for a better result.",
            "• Undoing a log that met a target takes that target and its treat back.",
            "• An empty jar means no rolls and no misses; you'll be asked to add a treat.",
            "• A one-time treat leaves the jar once won; a repeatable one stays.",
            "Tip: Use small, real treats you'd actually look forward to, and keep 5 to 10 in the jar. Make some one-time treats for bigger things. Never put something in the jar you'd feel bad about.",
        ),
        listOf("shop", "xp"),
    ),
    GuideArticle(
        "shop", "Rewards and the game", "Stock and the shop",
        "Save treats for later, or buy one with coins.",
        listOf(
            "• Stock: treats you won but haven't used yet, grouped by name with a count. Tap Use one when you enjoy one. Undo is there if you tap by mistake.",
            "• Coins: 1 coin for every 10 XP.",
            "• Shop: give a treat a price to put it on sale. Buy it when you have enough coins; it lands in your stock.",
            "• An unused purchase can be refunded.",
            "• If XP drops after undoing a day, the coin balance can dip below zero; nothing bought is taken back, and buying waits until it is positive again.",
            "Tip: Price bigger treats (a new game, a dinner out) in the shop so you save up for them, and keep small ones in the jar for surprise wins.",
        ),
        listOf("treats", "xp"),
    ),
    GuideArticle(
        "xp", "Rewards and the game", "XP, levels and the weekly boss",
        "Your hero grows with every day you show up.",
        listOf(
            "XP is the sum of the points you earned every day, plus bonuses. It builds your hero level: level 2 needs 100 XP, level 3 needs 250, then 450, and so on. Titles go from Rookie up to Legend.",
            "Bonus XP: a Titan day, finishing the daily quest, a full week of Blaze days, beating the weekly boss, and beating monsters. Bonuses for today are taken back if you undo the day; boss and monster bonuses stay.",
            "The weekly boss starts every Monday. Its health is 80% of your average weekly points over the last four weeks (at least 100). Every point you earn that week hits it. Beat it to win a treat. It never takes anything away.",
            "Tip: The boss is sized to your own recent weeks, so a steady week is enough. You don't need a heroic one.",
        ),
        listOf("dayscore", "quest", "monsters"),
    ),
    GuideArticle(
        "quest", "Rewards and the game", "The daily quest",
        "One item picked for you each day.",
        listOf(
            "Each day one thing from your plan is picked as the daily quest. It never changes during the day. Finish it for bonus XP. Missing it costs nothing.",
            "Tip: If you're short on time, do the quest first. It's a good way to make sure the day counts.",
        ),
        listOf("xp"),
    ),
    GuideArticle(
        "monsters", "Rewards and the game", "Monsters",
        "Name a struggle, then beat it with your habits.",
        listOf(
            "A monster stands for something you're fighting, such as Procrastination. Create one under Hero > Monsters, then link habits, goals and tasks to it with a power: light, medium or heavy.",
            "• Ticking a linked habit or goal damages it once per day; finishing a linked task damages it.",
            "• A linked quit habit damages it for each clean day; a slip heals it a little.",
            "• At 0 health it is beaten: a trophy, a treat draw and bonus XP. It then rests 3 days and comes back 25% stronger.",
            "• Undoing a tick takes the damage back. A recorded defeat or trophy is never undone.",
            "• Outside the Monsters screens, names show as \"a monster\" for privacy, unless you turn that off in Settings.",
            "Tip: Link only the 2 or 3 things that really fight that struggle. A monster you beat every couple of weeks is motivating; one that takes months isn't.",
        ),
        listOf("quit", "xp"),
    ),

    // ---- Reflect ----
    GuideArticle(
        "history", "Reflect", "History and insights",
        "Look back at days, weeks and months.",
        listOf(
            "Hero > History is a calendar of your past days with their level, plus day details, records and Blaze streaks.",
            "Insights, on the History screen, sum up a week or a month with a sentence you can share. Mood lines appear once you have at least 7 moods.",
            "Tip: A 2-minute look at last week's insights every Sunday tells you more than any single day.",
        ),
        listOf("review", "notes", "year"),
    ),
    GuideArticle(
        "notes", "Reflect", "Day notes and mood",
        "A private line and a mood for each day.",
        listOf(
            "Write a short note (up to 2,000 characters) and pick a mood from 1 to 5 for any day.",
            "Notes are private: they never show on widgets, notifications or shared text. They are included in your backups.",
            "They never change your score, XP, streaks or rewards.",
            "Tip: One sentence is enough: what went well, or what got in the way. After a few weeks, insights can show how mood lines up with your days.",
        ),
        listOf("history"),
    ),
    GuideArticle(
        "review", "Reflect", "Weekly review",
        "Look back, clean up, choose three things.",
        listOf(
            "Once a week the Today screen offers a short review: how the week went, a chance to tidy overdue tasks, a note, and up to three things to focus on this week.",
            "It only plans. It gives no XP and changes nothing else. Dismissing it costs nothing.",
            "Tip: Keep the three focus items concrete (\"3 gym sessions\", not \"get fit\") and check them on Today during the week.",
        ),
        listOf("history"),
    ),
    GuideArticle(
        "year", "Reflect", "Year in review",
        "Your year on one card.",
        listOf(
            "Year in review, on the Stats screen, counts your logs, active days, longest run, targets met, tasks done, treats won and bought, XP, Titan days and your most logged goal. Share it as an image; notes and moods are never on it.",
        ),
        listOf("history"),
    ),

    // ---- Phone and data ----
    GuideArticle(
        "reminders", "Phone and data", "Reminders and notifications",
        "Nudges at the right time, silence the rest.",
        listOf(
            "• Each goal can have its own reminder times.",
            "• Daily summary and evening check-in: Settings > Tasks and reminders.",
            "• Quiet hours make reminders silent (never skipped). You can turn off the reminder sound, and snooze a reminder for 10 minutes.",
            "• For reminders on time, allow exact alarms and exempt Jarvest from battery optimization when Settings asks.",
            "Tip: One reminder at the time you usually do a habit works better than several through the day.",
        ),
        listOf("widgets"),
    ),
    GuideArticle(
        "widgets", "Phone and data", "Widgets and shortcuts",
        "Log from the home screen.",
        listOf(
            "Long-press your home screen > Widgets > Jarvest. There are widgets for Today, Tasks, Streaks, the day Score, the Agenda and the Habit grid (the last 5 days; tap to tick).",
            "Long-press the app icon for shortcuts such as New task.",
            "A treat won from a widget is revealed the next time you open the app.",
            "Tip: Put the Today or Habit grid widget on your first home screen. Seeing it is half the habit.",
        ),
        listOf("today"),
    ),
    GuideArticle(
        "backup", "Phone and data", "Backup, restore and export",
        "Your data lives only on this phone. Keep a copy.",
        listOf(
            "Nothing is uploaded anywhere. That's private, but it means a lost or reset phone loses everything unless you have a backup.",
            "• Automatic backup: pick a folder once in Settings > Backup and data. A backup is saved there every day and the newest 7 are kept. Pick a folder that syncs to the cloud (such as Google Drive) for safety.",
            "• Back up now or share a backup file at any time.",
            "• Restore checks the file before replacing anything.",
            "• Export: your data as JSON, tasks as CSV, and logs.",
            "Tip: After setting up automatic backup, check once that files really appear in the folder.",
        ),
        listOf("start"),
    ),
    GuideArticle(
        "looks", "Phone and data", "Looks and feel",
        "Themes, motion, sounds and haptics.",
        listOf(
            "Settings > Look and feel. Each look changes more than colors:",
            "• Dark fantasy and Parchment: carved cards, Cinzel titles, painted art.",
            "• Retro pixel: pixel font, square windows, pixel art, block bars, pixel confetti.",
            "• Neon: glowing panels, Orbitron font, outline art, gauge bars, rings of light.",
            "• Clean: plain cards, system font, flat art, quiet effects. Follows the phone's light or dark setting.",
            "Motion, haptics and sounds can each be turned off. The phone's \"remove animations\" setting always wins.",
        ),
    ),
)

/** The section order in the guide. */
private val guideSections = listOf("Start here", "Goals and habits", "Tasks and calendar", "Rewards and the game", "Reflect", "Phone and data")

/** The guide article for a screen, for the "?" in the top bar. Null means no button. */
internal fun guideArticleFor(route: String): String? = when {
    route == "today" -> "today"
    route == "tasks" -> "tasks"
    route == "calendar" -> "calendar"
    route == "goals" -> "kinds"
    route == "habits" -> "habits"
    route == "more" -> "xp"
    route == "treats" -> "treats"
    route == "monsters" || route.startsWith("monster/") -> "monsters"
    route == "stats" -> "stats"
    route == "history" -> "history"
    route == "routines" -> "routines"
    route == "holiday" -> "skips"
    route == "review" -> "review"
    route.startsWith("focus/") -> "focus"
    route.startsWith("goal/") -> "stats"
    route == "settings" -> ""
    else -> null
}

/** The "?" button that opens the guide at [articleId] ("" opens the list). */
@Composable
fun GuideButton(articleId: String, onOpen: (String) -> Unit) {
    IconButton(onClick = { onOpen(articleId) }) {
        Icon(Icons.AutoMirrored.Outlined.HelpOutline, "Guide: how this screen works")
    }
}

/** The list of articles, grouped by section, with a search box. */
@Composable
fun GuideScreen(onOpen: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val words = query.trim().lowercase().split(" ").filter { it.isNotBlank() }
    val matches = guideArticles.filter { a ->
        val text = (a.title + " " + a.summary + " " + a.body.joinToString(" ")).lowercase()
        words.all { it in text }
    }
    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OutlinedTextField(
                query,
                { query = it },
                Modifier.fillMaxWidth(),
                placeholder = { Text("Search the guide") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
            )
        }
        if (matches.isEmpty()) {
            item {
                Text("Nothing matches \"$query\".", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        for (section in guideSections) {
            val inSection = matches.filter { it.section == section }
            if (inSection.isEmpty()) continue
            item(key = "s-$section") {
                Text(
                    section,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            items(inSection, key = { it.id }) { article ->
                SoftCard(Modifier.fillMaxWidth(), onClick = { onOpen(article.id) }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(article.title, style = MaterialTheme.typography.titleMedium)
                        Text(article.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** One article, with links to related ones. */
@Composable
fun GuideArticleScreen(articleId: String, onOpen: (String) -> Unit) {
    val article = guideArticles.firstOrNull { it.id == articleId }
    if (article == null) {
        GuideScreen(onOpen)
        return
    }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(article.section, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        Text(article.title, style = MaterialTheme.typography.headlineSmall)
        for (paragraph in article.body) {
            when {
                paragraph.startsWith("Tip: ") -> SoftCard(Modifier.fillMaxWidth(), glow = true) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Tip", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                        Text(paragraph.removePrefix("Tip: "), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                paragraph.startsWith("• ") -> Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    Text(paragraph.removePrefix("• "), style = MaterialTheme.typography.bodyLarge)
                }
                else -> Text(paragraph, style = MaterialTheme.typography.bodyLarge)
            }
        }
        val related = article.related.mapNotNull { id -> guideArticles.firstOrNull { it.id == id } }
        if (related.isNotEmpty()) {
            Text("Related", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            for (r in related) {
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(r.id) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { onOpen(r.id) }) { Text(r.title) }
                }
            }
        }
    }
}
