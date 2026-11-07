# Jarvest: V1 plan

A personal goals and habits app for Android. It keeps its data on the device,
uses a Rust core and a Jetpack Compose UI, and is shared with friends and testers
as a signed APK.

## Scope

**In V1:** yes/no, habit, and numeric goals; deadlines, categories, and ordered
milestones; a Today screen with one-tap logging; a calendar heatmap, completion
rates, and streaks; a Today checklist widget; archive and delete; a personal
reward jar with comeback boost; single-file SQLite backup and restore; and a
rewards off switch.

**Not in V1:** reminders, sync, encryption, app lock, surprise drops, weekly
chests, sub-goals, and line charts.

## Architecture

```
Compose UI + Glance widget (Kotlin)      screens, Android integration
        | generated bindings (UniFFI + JNA)
goal_core (Rust)                         rules, stats, rewards, backup
        | rusqlite (bundled SQLite)
goals.db (private app storage)
```

- All rules live in Rust and are unit tested on the computer, without a phone.
- Kotlin supplies today's date as `YYYY-MM-DD` using the phone's time zone. Rust
  never reads the clock for rule decisions, so date tests are deterministic and
  a time zone change can't rewrite history.
- The app and widget share one `GoalCore` instance in one process. A mutex
  serializes access, and each multi-step change runs in a transaction.

## Layout

```
core/                 Rust crate (library + uniffi-bindgen binary)
android/              Gradle project (app module)
scripts/              toolchain setup and release helpers
rust-toolchain.toml   pinned Rust version and Android targets
README.md             beginner-friendly setup, phone install, and sharing guide
```

## Domain rules

### Goal kinds

| Kind | Logging | Done when |
|---|---|---|
| Yes/no | One tick | Ticked once |
| Habit | At most one check-in per day | Each period's count target is met |
| Numeric | Add amounts, several per day allowed | Optional period and/or total target is met |

- Periods are day, week (Monday start), or month.
- Kind, period, and targets can't be edited after creation, because changing them
  would reinterpret history. Title, notes, category, unit, and deadline can.
- Milestones are ordered and ticked manually.
- Goals never become "archived" automatically. The user archives finished goals.

### Today screen

An active goal appears if it still needs work, or if it was logged today, so a
log made today can be undone from the same place. Cards keep a fixed order, so
they don't move while being ticked.

### Streaks and completion rates

- A streak is the number of consecutive periods with the target met.
- An unfinished current period doesn't break a streak, and counts once it's met.
- The period a goal was created in only counts if it was met, so a goal created
  late in a week isn't penalized.
- A completion rate covers the last 12 eligible periods, using the same rules.
- Overall stats exclude archived goals. Their history stays intact.

### Heatmap

Shows the number of log entries per day for one goal or for all active goals.

### Rewards

A **target** is a met period, a met total, a ticked yes/no goal, or a ticked
milestone.

1. Each target pays out at most once. Completions are unique per
   `(goal, target_key)`.
2. If rewards are off, no roll happens. If the jar is empty, no roll happens, the
   result is `JarEmpty`, the UI prompts for a treat, and nothing counts as a miss.
3. Base odds are 1 in 5. A comeback doubles them to 2 in 5. A comeback is a
   completed repeating period whose previous period was missed, unless that
   previous period is the goal's creation period.
4. The pity rule guarantees a draw on the 10th roll since the last draw.
5. Rolls come from `SHA-256(install_seed, goal_id, target_key)`, so undoing and
   re-logging can't re-roll for a better result.
6. Undoing a log removes any target it no longer satisfies, and the reward from
   that target, even if the reward was already redeemed.
7. Rewards never expire and there's no paid currency or penalty.
8. A draw made from the widget stays "unseen" and is revealed when the app opens.

## Data model (SQLite)

`meta` (seed, settings), `goals`, `milestones`, `entries`, `target_completions`
(unique per goal and target key), `treats`, and `reward_draws`.

- `PRAGMA application_id` identifies our files, and `user_version` drives ordered
  migrations.
- A partial unique index enforces one check-in per day for yes/no and habit goals
  at the database level.
- Treats are soft-deleted, so past draws still show their titles.

## Backup and restore

- **Export:** `VACUUM INTO` a temp file, then stream it to a location chosen with
  the Android file picker. This produces one consistent file.
- **Restore:** copy to a temp file, then validate: application ID, schema version
  not newer than the app, `integrity_check`, and no triggers or views (a backup is
  untrusted input). Migrate the copy, keep a `before-restore` safety copy, and swap
  files atomically. An invalid file leaves current data untouched.
- Android Auto Backup and device-to-device transfer are disabled, so a live database
  can't be copied or restored out of band.

## Android

- Minimum Android 8 (API 26), target API 36, compile SDK 37 (required by current
  Compose libraries), Material 3, Compose, Glance widget.
- `arm64-v8a` for phones, `x86_64` for emulators.
- Bottom tabs: Today, Goals, Stats, Treats. Settings is reached from the top bar.
- Widget: ticking a yes/no goal or habit logs directly, numeric goals open quick
  entry, and the list refreshes after changes and periodically so it rolls over
  after midnight.

## Milestones

Each milestone ends with its check passing. All are complete. Milestones 2 to 5
were built as one Rust pass followed by their screens, because they share tables
and rules. Each was still checked on an emulator in order.

| # | Milestone | Done when |
|---|---|---|
| 0 | Toolchain: NDK, Rust Android targets, cargo-ndk, compatible JDK | Tools report versions |
| 1 | **Thin slice:** create a yes/no goal and log it through Compose, Rust, and SQLite | App runs, and the log survives a restart |
| 2 | Full goal model: kinds, periods, categories, deadlines, milestones, archive, delete, full Today | Core tests pass; screens work |
| 3 | Stats: streaks, completion rates, heatmap | Boundary tests pass; screen works |
| 4 | Rewards: jar, odds, pity, comeback, undo, off switch, reveal | Rule and distribution tests pass; flow works |
| 5 | Backup and restore | Round trip and bad-file tests pass; flow works |
| 6 | Today checklist widget and numeric quick entry | Widget ticks and refreshes |
| 7 | Signed release APK and README | Release build installs and launches |

## Testing

- **Rust:** `cargo test` covers period boundaries (year, month, leap day), streaks,
  undo, rewards, migrations, and corrupt backups.
- **Android:** the APK must build, then get exercised on an emulator or phone.
  Anything that can't be run gets reported as unverified.

## Tooling notes for this machine

- Rust comes from Arch's `rust` package, which has no Android targets. Install
  rustup into `~/.cargo` and `~/.rustup` with `--no-modify-path`, leaving the
  system Rust and shell config untouched.
- Install the NDK with the existing `sdkmanager`.
- Java 27 works with Gradle 9.8.2, so no separate JDK is needed.
- Glance depends on an old WorkManager that crashes under current R8, so the app
  pins a newer one. Always smoke test release builds, not just debug builds.
- Nothing requires `sudo`.

# V0.2.0 plan

Rule changes come first because streaks, stats, and rewards depend on them. Each
step ends with `cargo test` passing. Every schema change is a new numbered file in
`core/src/sql/` that is added to `MIGRATIONS` in `core/src/db.rs`.

## Decisions

- A skipped or paused day keeps the streak alive. Skipped periods are left out of
  completion rates.
- Logs for past days can complete targets and earn rewards.
- Sub-goals are not built. Ordered milestones cover "Prep quiz > Lec 1, Lec 2".

## Step 1: Rules (Rust, migration 002)

1. **Log past days.** `toggle_check_in` and `log_amount` take the day to log, next
   to today's date. Reject future dates and dates before the goal's creation date.
   `sync_targets` already takes the changed day separately from today, so
   completions and `comeback` follow the logged day. The yes/no tick lookup must use
   that day. Reward rolls already hash `(seed, goal_id, target_key)`, so a
   backfilled target rolls the same result as a live one. The pity counter orders
   by completion `seq`, so a backfill counts as the newest roll. Add tests for
   undo, re-log, and backfill across a period boundary.
2. **Skip day and pause.** A new `skips (goal_id, local_date)` table. Pausing a
   goal writes a range of skip days. An unmet period that contains a skipped day is
   excused: it neither breaks the streak nor counts in the completion rate. A met
   period still counts normally. Update `period_summary` in `core/src/stats.rs`.
3. **Specific weekdays.** A nullable `weekdays` bitmask column on `goals`, usable
   by day-period habits only. Days outside the mask are not eligible, so they don't
   break streaks and don't appear on Today. Like kind and period, the mask can't be
   edited after creation.

## Step 2: Insight (Rust queries, then Stats screen)

- Line chart data: per-period totals for a goal (reuse `period_totals`), and
  completion rate per period.
- Weekly and monthly summary: periods met and missed, entries logged.
- Best and worst weekdays: share of eligible days met, per weekday.
- Goal comparison: rates by goal and by category.
- Streak history: `best_streak` already exists in `GoalStats`; show it, plus a list
  of past streaks.
- Milestone progress on goal cards ("3 of 8"): check whether the card shows it
  already.

## Step 3: Rewards

- Reward history: `list_draws` already exists; add a screen section.
- Badges: derived from existing data (streak lengths, check-in counts), so no new
  table is needed. Compute them on read.

## Step 4: App polish (Kotlin, mostly independent)

Dark mode and themes (`Theme.kt`, `SettingsScreen.kt`), goal templates, search and
filter, reorder goals (needs a `position` column, migration), more widgets
(`TodayWidget.kt` pattern), CSV export (written to a file chosen with the file
picker, like backup).

## Release

Bump `versionCode` to 2 and `versionName` to `0.2.0` in
`android/app/build.gradle.kts`. Smoke test the release build on an emulator or phone,
and test upgrading an install made from 0.1.0 so the migrations run on real data.

# V0.3.0 plan

## Reminders

- Times are stored in the database (`reminders` table, plus `daily_reminder_minutes` in
  `meta`), so backups keep them. `core/src/reminders.rs` decides what a reminder says
  and stays silent when nothing is due, using the Today list.
- Android sets one inexact alarm per reminder (`AlarmManager.setAndAllowWhileIdle`, no
  exact-alarm permission). Alarms are rebuilt after every change, on app start, on
  boot, and after an app update. Each alarm sets the next day's alarm when it fires.
- The notification permission (Android 13 and newer) is requested the first time a
  reminder is turned on.

## Counter

Numeric goals show `-`, a count, `+`, and Submit. Each tap changes the count by 1.
Submit logs one entry for the count and resets it to 0. The count is a draft kept in
memory until submitted. "Exact" opens the old amount dialog for decimals.

## Checked on an emulator (Android 16, x86_64)

Upgrade from 0.2.0 keeps data; counter up, down, and submit; notification permission
prompt; daily summary fired and set tomorrow's alarm; goal reminder time picker; alarms
come back after a boot broadcast; Stats, Goals, and Treats open without crashes.
Not checked: the widget's quick-entry dialog, a denied notification permission, and a
real reboot.

# V0.4.0 plan

## Tasks

- Tasks are separate from goals: own tables (`tasks`, `task_items`, migration 006), own
  module (`core/src/tasks.rs`), and no rewards, streaks, badges, or stats.
- A task has a title, notes, due date, priority (low, medium, high), one tag, an optional
  repeat (daily, weekdays, weekly, monthly), and a checklist.
- Finishing a repeating task creates the next copy, due after the old due date and never in
  the past. Unticking it removes that copy unless it was already finished.
- `parse_quick(raw, today)` reads `today`, `tomorrow`, `in N days/weeks`, `next week`,
  weekday names, `YYYY-MM-DD`, `5 nov`, `nov 5`, a final `d/m`, `!high|med|low`, and `#tag`.
  English only.
- Deleting moves a task to the trash. `purge_trash` removes tasks deleted 30 or more days ago.
- The Tasks tab has filters (All, Today, Overdue, Done) and sorting (Due, Priority, Newest, A to Z).
  Today shows a "Tasks due" section above the goals.

## Clearer screens

- Reusable `TipCard`, `InfoSheet`, and `InfoHint` in `Guidance.kt`. Dismissed tips are stored in
  the `ui` preferences, not in the database, so backups don't carry them.
- Tapping a goal on Today opens a details sheet. The Today card now shows only the title,
  progress, and controls.
- "?" buttons explain goal types, the counter, and skip versus pause.

## Not done yet

Task calendar and focus timer, tasks in the widget, a details sheet on the Goals list,
Arabic quick add, and hints on the Treats screen.

# V0.5.0 plan

## Tasks linked to goals

- `tasks.goal_id` (migration 007, `ON DELETE SET NULL`). The link only groups tasks on the goal's
  page with a progress count. Finishing a linked task never logs the goal, earns a reward, or
  changes stats. Milestones are unchanged and still count as targets.
- A task can keep its link to a goal that was archived later. A new link needs an active goal.

## Rewards made visible (rules unchanged)

- Odds (1 in 5, 2 in 5 after a comeback), the pity rule (a treat within 10 targets), and undo are
  exactly as in V1.
- `reward_progress()` gives the countdown: `guaranteed_in` is how many more targets until a treat is
  certain (1 means the next one). `LogResult` now says `no_treat`, `drew`, and `treats_removed`, so the
  app can tell the user what happened to every target and to every undo.
- An empty jar is shown as a banner on Today and Rewards for as long as it is true.

## Stats that show direction

- `GoalStats` and `StatsOverview` carry `prev_met` and `prev_eligible`: the 12 eligible periods before
  the 12 already counted. Skipped periods are excluded from both windows.
- `momentum(today)` compares the two windows across active goals. A move of 5 percentage points or
  more is Advancing or Slipping. Fewer than 3 eligible periods in either window gives Not enough data.
  The reason names the goal that moved most (15 points or more).
- Stats opens with the verdict. Trend chips show on a goal only when it moved 5 points or more.
  Summary compares logs, active days, and targets met with the period before. The streaks widget shows
  the verdict as words.

## UX fixes from the critique

Labelled add buttons (Add menu on Today), Rewards tab name, a "done today" line, at most three tasks
on Today, Undo after every task tick, confirm before removing a treat or milestone, pinned and
disabled task save button, quick-add chips and a warning for unknown `!priority` words, "Not saved yet"
on the counter, the Today widget shows task count and progress, the best streak names its unit and goal.

## Not done yet

Collapsing the Goals filters and the goal editor, larger heatmap tap targets, "empty trash", days left
on trash rows, and a different icon for priority than for the Goals tab.

# V0.5.1 plan

## Milestones as tasks

- Every milestone has a mirror task (`tasks.milestone_id`, migration 008, `ON DELETE CASCADE`), so it
  also shows in the Tasks tab with the goal's name. Existing milestones get a task in the migration.
- The milestone is the source of truth and the reward target. Ticking the task goes through the
  milestone, so it can win or lose a treat exactly like ticking the milestone. This is the one case
  where finishing a task earns a reward. Ordinary tasks, including ones linked to a goal, still never do.
- Renaming the task renames the milestone. Due date, priority, notes and tag live on the task only.
- Deleting the milestone deletes its task. A milestone's task can't be moved to the trash, moved to
  another goal, or repeat, so the pair can't drift apart.
- `add_milestone` now takes today's date, for the new task's created date.

# V0.5.2 plan

Small details that GoalTerra already had, found by comparing the two apps.

- The daily summary reminder counts open tasks due today or earlier, and calls out overdue ones:
  "1 goal and 2 tasks (1 overdue) left today: Read, Pay rent, Call mum". Tasks alone are enough to
  send it. A goal's own reminder is unchanged.
- Due labels: up to 7 days away they read "Tomorrow" or "N days left", further away they show a short
  date. Late is red, due within 3 days is amber, anything later is calm. Words always come with the color.
- The Tasks tab remembers its filter and sort. The Overdue chip shows a count. New tasks start with a
  default priority and due date from Settings. Quick add uses the same defaults for what the line leaves out.
- Last backup date: kept on the phone (not in the database, so restoring an old backup can't make a stale
  date look fresh). Today shows a card when the last backup is over 7 days old (over 30 days, or never
  with 5 or more items, is shown as urgent). "Later" hides it for 3 days, or 1 day when urgent.
- New tasks, new goals, the "Add a step", milestone, and treat fields: the cursor starts in the title
  field of a new task or goal, and the keyboard's Done key adds a step, milestone, or treat.
- Fixes: "1 weeks in a row" now reads "1 week in a row", and checkboxes have spoken names for TalkBack.

# V0.6.0 plan: the polish and power release

Goal: close the gap with GoalTerra's everyday feel, and add the features a daily user reaches for.
This is a large release, so it is built in six phases. Each phase ends with `cargo test` passing and a
debug build that installs, so a problem is found where it started. Phases are ordered by value and by
how much each one depends on the one before.

Effort: S is under half a day, M is one to two days, L is more.

## Rules that stay the same

Rust never reads the clock. Kind, period, and targets are immutable after creation. Reward rolls stay
deterministic and unchanged. Backups stay untrusted input. Never edit a released migration. Ordinary tasks
never earn rewards (a milestone's task is the one exception, because the milestone is the target).

## Schema changes (new migrations only)

- `009_task_archive.sql`: `tasks.archived_date TEXT` (an archived task is hidden, not deleted).
- `010_goal_trash.sql`: `goals.deleted_date TEXT`, so deleting a goal moves it to the trash with its
  entries, milestones, and rewards intact. Every query that lists goals must skip trashed ones.
- `011_icons_and_samples.sql`: `icon TEXT` on `goals` and `tasks`, and `sample INTEGER DEFAULT 0` on both,
  so sample data can be cleared without touching real data.
- `012_avoid_habits.sql`: `goals.avoid INTEGER DEFAULT 0` for quit habits (see Phase 4).
- `meta` keys, no migration: `week_start` (1 = Monday, 6 = Saturday, 7 = Sunday).

`SCHEMA_VERSION` is derived from the list in `core/src/db.rs`, so each file is one new line there.
Backup validation compares the whole schema, so new columns need no change, but each new date column
(`archived_date`, `deleted_date`) must be added to the invalid-date check in `core/src/backup.rs`, gated
on the version that introduced it.

## Phase 1: Data safety (highest value)

1. **Automatic daily backup (M).** A WorkManager job (the app already pins a newer `work-runtime`) runs at
   a time chosen in Settings, default 21:00. It calls `export_backup` into the app's `Documents/Jarvest`
   folder through the Storage Access Framework tree the user picks once, writes
   `jarvest-auto-YYYY-MM-DD.sqlite`, keeps the last 7, skips an empty database, and updates the last-backup
   date. A failure shows a notification instead of failing silently.
2. **Restore preview (M).** New `preview_backup(path)` in `core/src/backup.rs` opens a validated copy and
   returns counts (goals, tasks, entries, treats), the newest entry date, and the schema version. The
   restore dialog shows a "Backup vs Now" table, highlights rows where the backup has less, and says
   "extra items will be gone" with a red Restore button when data would be lost.
3. **Stale-backup notification (S).** If the last backup is over 7 days old, schedule one gentle
   notification at 12:00 tomorrow, using the alarm code in `Reminders.kt`. Cancel it after a backup.
4. **Theme and accent in the backup (S).** Move them from `SharedPreferences` into `meta`, so a restore
   brings the look back too. Tips and the backup date stay on the phone.
5. **Snapshot audit (S).** Confirm in `restore` that a failure after the swap rolls back to the
   `before-restore` copy, and add a test that forces one.
6. **Clear failure messages (S).** Distinguish an empty file, a truncated file, and a file from a newer app.

## Phase 2: Task power

1. **Long-press and bulk select (M).** Long-press a task to enter Select mode (520 ms, cancel on movement).
   A bar offers Done, Edit (due date, priority, tag, goal), Archive, and Move to trash, with one Undo that
   restores the whole batch. Back or Cancel leaves the mode. New core call `bulk_update_tasks(ids, edit)`
   runs in one transaction. Milestone tasks are skipped by Edit, Archive, and trash, with a message.
2. **Row overflow menu (S).** A "more" button on each row with Edit, Archive, and Move to trash.
3. **Task archive (M).** Migration 009. An Archived screen under Settings with Restore and "Delete
   completed". An optional "Archive finished tasks after 0, 1, 7, or 30 days" setting, run by the core on
   open with the date passed in. A milestone's task is never archived.
4. **Goal trash with Undo (L).** Migration 010. Deleting a goal sets `deleted_date`, shows an Undo
   snackbar, and lists it in a shared Trash screen next to tasks. Purge after 30 days. This touches
   `list_goals`, `today_items`, stats, badges, reminders, widgets, and CSV export, so it needs its own
   test for each place a trashed goal must not appear.
5. **Trash details (S).** Show "N days left" per row, "Delete forever", and "Empty trash" with a confirm.
   `Task` gains `deleted_date`.
6. **Tags (M).** A `list_tags()` call, suggestion chips under the tag field, a tag filter row on the
   Tasks tab, and optional grouping into collapsible `#tag` sections with a done count and a thin progress bar.
7. **Done list and row detail toggles (S).** A collapsed "N done" section under open tasks, plus Settings
   toggles for notes, checklist progress, and compact rows.
8. **Info sheet extras (S).** Created date, "pushed back N times" (needs a counter on `tasks`, set when the
   due date is moved later), and the checklist.
9. **Custom repeat (M).** "Every N days/weeks/months" and "on these weekdays", reusing the weekday mask
   already used by habits. `next_due` in `core/src/tasks.rs` gets the new rules and tests.
10. **Subtask-style checklist editing (S).** Edit a checklist as one text block, one step per line.
11. **Search (M).** A `search(query)` call over tasks (title, notes, tag, checklist) and goals, capped at
    30 results each. A search icon on Today opens a results screen with "Archived task" style labels.
12. **Confirm toggles (S).** "Ask before deleting" per kind, with a note that Undo is always offered.

## Phase 3: Goals and habits

1. **Quit habits (L).** Migration 012. A goal can be a "quit" goal: it is met by *not* logging. A day with
   no slip counts as clean, and the streak counts clean days. Logging a slip breaks it. Needs new rules in
   `core/src/stats.rs`, a `PLAN.md` rule section, and careful tests for creation day and skipped days.
2. **Week start setting (M).** `week_start` in `meta`, threaded through `period_start`, the heatmap columns,
   the weekday chart, and recap. Changing it never rewrites history, because periods are computed on read.
3. **Month calendar with tap to toggle (M).** On the goal page, a month grid showing met, missed, skipped,
   and today. Tapping a past day toggles the check-in (reusing `toggle_check_in_on`). A star marks the
   creation day.
4. **Folders (M).** Group goals by category with collapsible headers, a done count, "All done", rename,
   and reorder, replacing the wall of category chips.
5. **Goal editor in steps (M).** Only title and type up front, with deadline, reminder, notes, and
   milestones under "More options".
6. **Goals list filters collapsed (S).** One "Filter" row that opens the kind, category, and archived options.
7. **Reorder mode (S).** A "Reorder" button with drag handles instead of up and down arrows that vanish
   when a filter is on.
8. **Per-item icons (M).** Migration 011. An emoji or icon picker for goals and tasks, shown on cards,
   rows, and widgets.

## Phase 4: Look, feel, and accessibility

1. **Pop animation on tick (S)** and a larger celebration when a goal's target is met.
2. **Haptics (S).** A light tick on check, undo, and long-press, with a Settings switch.
3. **Sounds (M).** Short generated tones (task, habit, undo, goal) with a Settings switch and a preview.
4. **Black (OLED) theme and more accents (S each).** Add a Black scheme to `Theme.kt`, and Rose, Teal,
   Amber, and Graphite accents. Contrast is checked by a unit test (item 6).
5. **Reduced motion (S).** Skip the reward bounce and the pop when the system animation scale is 0.
6. **Contrast and semantics tests (M).** A JVM unit test that computes WCAG contrast for every text and
   background pair in `Theme.kt`, light and dark, for all accents. A Compose UI test that every clickable
   control has a name.
7. **Different icon for priority and for Goals (S).** Priority uses a signal-style icon, Goals keeps the flag.
8. **Settings in groups (S).** Collapsible sections with their open state remembered, and a version row
   that opens a "What's new" sheet fed from a changelog list in the app.
9. **Keyboard polish (S).** Hide the bottom bar while typing, scroll the focused field into view.
10. **Empty-state copy (S).** "Nothing due today. 2 goals off today." and an "All done" line.
11. **Bigger heatmap tap targets (S).** A week-row tap shows a readout of that week.

## Phase 5: Onboarding and first run

1. **First-run intro (M).** Four short steps: goals vs tasks, milestones, rewards and the countdown, and
   backups. Back, Skip, and dots. "Show intro again" in Settings.
2. **Sample data (M).** Migration 011. "Try it with sample data" creates a few flagged goals and tasks with
   a week of history. A banner says "Sample data is on", and "Clear sample data" removes only flagged rows
   (rewards earned from samples are removed with them).
3. **Template bundles (M).** "Get fit", "Learn a skill", and "School year" create two or three goals plus
   starter tasks in one tap, from the empty Today screen and from the new-goal screen.

## Phase 6: Reports, notifications, and bigger features

1. **Weekly and monthly report with tasks (M).** `Recap` gains `tasks_done`, `tasks_missed`, and the missed
   tasks with their due dates. The Summary card gets a "Missed tasks" list and a best-week line for months.
2. **Evening check-in and test notification (M).** A second optional time that says "N goals and M tasks
   left today", only when something is left. A "Send a test notification" button that also explains a
   denied permission.
3. **Task calendar (L).** A month grid of due and finished tasks. Tap a day to see it, and add a task on that
   day. Needs `tasks_in_range(from, to)` in the core.
4. **Focus timer (L).** A 25-minute timer (length is adjustable) from a task. It keeps time when the screen
   locks (a foreground service with a notification), and the minutes are saved on the task (`focus_minutes`).
5. **Tasks widget and quick-add widget (M).** A widget listing tasks due, with tick in place, and a small
   widget that opens quick add.

## Order and cut line

Build Phases 1, 2, and 4 first, because they are the details people notice daily. Phases 3 and 5 follow.
Phase 6 items 3 and 4 (calendar and focus timer) are the first to move to 0.7.0 if the release grows too
large. Quit habits (Phase 3, item 1) and goal trash (Phase 2, item 4) carry the most risk, so each gets
its own commit and its own test pass before anything else is built on top.

## Verification for the release

- `cd core && ~/.cargo/bin/cargo test`, `cargo clippy --all-targets`, `cargo fmt` after every phase.
- Upgrade tests: a test per migration builds a database at the previous version with data in it, opens
  it, and checks the data survived and the new column is usable.
- Backup tests: a v8 backup restores into the new app and upgrades, and a backup with a bad new date is rejected.
- A performance test: 5,000 tasks and three years of logs render the Today and Tasks lists without a slow query.
- On a phone or emulator: upgrade from 0.5.2 keeping data, run each phase's checklist, rotate the
  screen, switch the theme and accent, turn on TalkBack for the Today and Tasks screens, and test with
  the notification permission denied.
- Release: `./gradlew assembleRelease`, smoke test the release APK (R8 has broken things before), and test
  an upgrade install over the previous release.
- Anything that cannot be run is reported as unverified.

## Quit (avoid) habits, as built in 0.6.0

- A quit habit is a daily habit created with `create_avoid_goal` (`goals.avoid = 1`, period `day`, target 1,
  no weekday schedule). Kind, period, and the flag are immutable like any other goal.
- Every entry on it is a **slip** (one per day, toggled with `toggle_check_in_on`). A day with no slip is a
  **clean day**. For stats, slip counts are turned into clean-day counts (`stats::clean_days`) and the normal
  streak walk runs on them with a target of 1.
- Today counts: a clean day so far is met, so the streak includes today until a slip. Unlike other goals the
  quit streak has **no grace** for the creation day or today: a slip there counts straight away.
- A skipped (paused) day is excused when it holds a slip, and is neither a break nor a count when unmet.
- Quit habits **never earn rewards**: a clean day passes without any action, so there is no moment to roll,
  and `sync_targets` returns early for them. This keeps rolls deterministic and makes undo trivial.
- They stay on Today all day (`complete` = no slip today) so a slip can be logged from there.
- Week start: `meta.week_start` (1, 6 or 7). Periods are computed on read with `period_start_ws`.
- Folders: `meta.folder_order` holds folder names, one per line; `rename_category` renames on all goals.

## V0.6.0 as shipped

Built: automatic daily backup and restore preview; task archive, bulk select, tags, search, trash details; icons, sample data, onboarding, OLED theme; quit habits, week start, month calendar, folders; goal trash, evening check-in, tasks and quick-add widgets.
Not built, moved to 0.7.0: task calendar, focus timer, custom repeat screen and checklist editing (core `set_task_repeat_rule` / `replace_task_items` exist but have no UI), tag grouping and confirm-before-trash settings (stored, not used yet).
Never run on a device; test the upgrade from 0.5.2, auto backup, widgets and reminders on the phone.

## V0.7.0 plan: day score and level (core), polished Today

The day score answers "how is today going?" with one number. It is computed in the Rust core from `today` (no clock), and it never changes any data, rewards, or stats.

**Points.** Everything planned for today is worth points; what is done today earns them.
- Each goal or habit on today's list (`today_items`) is worth 10 points and earns them when its card is `complete` (a quit habit is complete while it has no slip).
- A task is worth 5 (low), 10 (medium) or 15 (high) points. A task counts if it is open and due today or earlier, or if it was finished today. Milestone tasks are left out, because their goal already counts. Trashed and archived-while-open tasks are left out.
- `possible` is all planned points, `earned` is the done part, `percent = earned * 100 / possible` rounded down, and 0 when nothing is planned.

**Level of the day.** None while nothing is done or nothing is planned. Spark from 1% to 49%. Blaze from 50% to 99%. Titan at 100%. Finishing everything on a day with a plan is always Titan. The score also reports the points left to reach the next level.

Not in 0.7.0 (planned for 0.8.0): hero XP and levels that never go down, the weekly boss and bestiary, a calendar of past day levels (needs a stored daily snapshot and a migration).

## Custom monsters (shipped in 0.7.1)

A monster stands for a struggle the person names, such as a habit they want to beat. They link habits, goals and tasks to it, and doing those things damages it. It is a display layer: it never changes goals, streaks, stats, or the treat odds.

**A monster has:** a name (up to 60 characters), a starting health (default 100, set by the person), a round number (starts at 1), and its current health.

**Links.** Each linked habit, goal or task has a power: light (5), medium (10) or heavy (20) damage.
- A habit or goal deals its power when it is ticked on a day, once per day. A task deals its power when it is finished.
- A quit habit deals its power for each clean day. A day is clean when nothing is logged on it and the habit existed that day. Clean days are counted when the app opens, up to today, so a closed app does not lose days.
- Damage is derived from history: health = max health for the round minus the damage done since the round started. Undoing a tick takes that damage back. Nothing is stored that could drift.

**Slips.** A slip on a linked quit habit heals the monster by half of that habit's power, never above full health. A slip never removes damage from past rounds and never removes a trophy. The message is kind: a slip is not a failure.

**Defeat and respawn.** At 0 health the monster is beaten: a celebration, one trophy in the trophy list, and one treat draw from the jar (or the treat attached to that monster, if any). With no treat in the jar there is only a celebration. The monster then rests for 3 days. Damage on rest days does not count. After the rest, the next round starts at full health, which is 25% higher than the round before (rounded up). Round 1 uses the starting health the person set.

**Privacy.** Widgets, reminders, notifications and the share sheet show "Monster" instead of the name unless the person turns that off in Settings.

**Core.** Rules live in a new `core/src/monsters.rs`. `today` comes from the caller. New tables (migration 013): `monsters`, `monster_links` (monster, kind goal or task, item id, power), `monster_rounds` (monster, round number, start date, defeated date). Health and damage are computed from entries and task completions, so restore and undo stay consistent. Backups validate the new dates, gated by schema version.

**Not in this release:** hero XP and levels, the weekly boss, and the calendar of past day levels.

## V0.8.0 (shipped): XP, weekly boss, reliable reminders

- XP is the sum of every day's points, kept in `day_scores` (migration 014). Level n needs `25 * (n-1) * (n+2)` XP (100, 250, 450, ...). Titles: Rookie 1-2, Apprentice 3-4, Ranger 5-7, Knight 8-11, Champion 12-16, Hero 17-24, Legend 25+.
- Weekly boss: starts Monday, health is 80% of the average of the last four weeks with points (rounded up to 10, minimum 100). Points earned in the week are the damage. One treat when it falls, never anything taken away. Name picked from a fixed list by the week.
- The day score shows in the Today widget and in the evening check-in text.
- Reliability: exact alarms when allowed, battery-optimization exemption button, alarms rebuilt after reboot, update, time or time zone change. No foreground service (chosen over a persistent notification to save battery).
- Moved to 0.8.1: day-level calendar (data is already stored in `day_scores`), task calendar, custom repeat and checklist editing UI.

## V0.9.0 (shipped): calendar and time blocking

- New Calendar tab: a week strip to pick a day, and that day hour by hour. Overlapping blocks share the row width. A line marks the current time on today.
- Blocks: events (title, day, start, length, repeat none/daily/weekly on chosen days), tasks on their due day (optional start time and length), goals and habits on the days they are planned (optional time block).
- Anything without a start time sits in an "Anytime" strip and counts as 30 minutes.
- Tap a block to finish a task, set a start time and length, or open it. Dragging blocks and event reminders are not built.
- The bottom bar became Today, Tasks, Calendar, Goals, Habits, More. Stats and Rewards (and Settings) live under More.
- Task rows keep the relative due label (Today, Tomorrow, "3 days left", then "Nov 12") and add the start time when set.
- Not built: custom repeat and checklist-editing screens, goal time block inside the goal editor (set from the calendar sheet), drag to move blocks, event reminders.

## V0.10.0 (shipped): the 0.10 to 0.20 roadmap built as one release

Schema went from 13 to 19 (migrations 014 to 019). Everything below compiles and is covered by Rust tests (235), but none of the screens were tested on a device.

- **Calendar, finished:** events with end dates, reminders and "skip this day" for one repeat, a month overview, drag to move blocks, a goal time block in the goal editor, and suggested times for plans without one (`insights.rs`, nothing saved until Apply).
- **Tasks:** quick-add times and lengths ("Essay tomorrow 3pm 1h"), templates (`templates.rs`, migration 017), custom repeat, editable steps, grouping by tag, clearer empty-trash messages.
- **History and insights:** history calendar with day details, records and Blaze streaks; weekly and monthly insights with a share sentence. Both only read data.
- **Focus timer** (`focus.rs`, migration 018): sessions for a task or goal, countdown in an ongoing notification, bars in history. No foreground service. Focus never changes score, XP, streaks or rewards.
- **Gamification II** (migration 019): `xp_bonuses` pays a Titan day (+10), a finished daily quest (+10), each full week of Blaze days (+20), a boss defeat (+25), a monster round (+15) and a final monster (+60). Titan, quest and streak bonuses are taken back if the day is undone; boss and monster bonuses stay. The daily quest (`daily_quests`) is picked once per day by hash and never changes. Missing it costs nothing. Seven new badges.
- **Notifications and widgets:** quiet hours (silent, never skipped), a reminder sound switch, a Snooze 10 min button, Score and Agenda widgets. These are phone-side prefs in `Reminders.kt`.
- **Data:** tasks CSV export, JSON export, tasks CSV import (bad rows skipped, 1000 rows at most), share backup through a cache-only `FileProvider`.
- **Polish:** 164 plain texts moved to `strings.xml`, content centered at 720dp on tablets and in landscape, an upgrade test that opens a database from every older schema version, backs it up and restores it.

### Not built or cut

- Backup password: needs encryption libraries. Not built.
- Boss modifiers and the bestiary: cut.
- Texts that contain values (counts, dates) are still in the Kotlin code. No translations were planned.
- A TalkBack pass on every screen: only checked that icon buttons have descriptions.
- The empty-trash "does nothing" report is still not reproduced. Core tests pass.

### Before 0.10.1

Run the README "Device checklist before a release" on a phone, install over 0.9.0, and smoke test the release APK (R8 has broken things before).

# V0.11.0 plan

Starting point: `versionName` 0.10.0, `versionCode` 14, schema 19, 235 Rust tests passing, clippy clean. Nothing in this plan is built yet.

One release in ten stages. It ends at `versionName` 0.11.0, `versionCode` 15, schema 23 (migrations 020 to 023).

Effort: S is under half a day, M is one to two days, L is more.

## Rules for every stage

- One commit per stage, ending with `cargo test`, `cargo clippy --all-targets` and `cargo fmt`, and a debug build that installs.
- 0.10.0 shipped without any of its screens tested on a device. So stage 1 comes first and needs a phone, and stage 10 repeats the check on the release APK. Anything not run is reported as unverified.
- Each migration gets an upgrade test. Each new date column gets a check in `backup.rs`, gated on its schema version. Each query that lists goals skips trashed ones.
- All the rules in `CLAUDE.md` stay. Nothing here changes reward odds or reads the clock in Rust.

## Stage 1: prove 0.10.0 works and fix it (M)

1. Run the README device checklist on a phone, over 0.9.0. Fix what breaks.
2. Reproduce the "empty trash does nothing" report. The code path looks right: `TasksScreen.kt` calls the core, `refresh()` bumps `version`, and the lists reload on it. One lead: tasks and goals have separate Empty buttons, and each leaves the other kind in place. If that is the cause, make the screen clearer.
3. `backup.rs` does not check the dates in `xp_bonuses` and `daily_quests`. Add the check, gated on `version >= 19`, with a test that rejects a bad date.
4. `insights.rs` always starts the week on Monday and ignores `meta.week_start`. Make insights follow the setting. Weekly bosses stay on Monday.
5. README: "Alarms are inexact" is out of date since 0.8.0, and Features does not mention the calendar, focus timer, monsters, XP or insights.

## Stage 2: safety net (M)

1. A CI workflow for pushes and pull requests (`release.yml` only runs on tags and by hand): `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings`, `cargo test`, `./gradlew testDebugUnitTest assembleDebug -Parm64Only=true`.
2. A Compose test in a new `androidTest` folder that opens every tab with sample data and checks that every clickable control has a name. Run it with `./gradlew connectedDebugAndroidTest`.
3. A Rust speed test with 5,000 tasks and three years of logs, covering `today_items`, `list_tasks`, `calendar_day` and `progress_tick`. Add indexes through a new migration only if a query is slow.
4. Split `core/src/tests.rs` (5,045 lines) into one file per module. The test count must stay the same.

## Stage 3: capture (S)

1. Share to Jarvest: an intent filter for shared `text/plain` opens the quick-add box, filled in and parsed by `quickadd.rs`. Nothing is saved until the person confirms, because shared text is untrusted.
2. App shortcuts: New task, Today, and Start focus. A focus session belongs to a task or goal, so Start focus opens a list to pick one. Build that list if the app has none.
3. A quick settings tile (`TileService`) that opens quick add.

## Stage 4: holiday mode (M, migration 020)

`pause_goal` pauses one goal at a time. This pauses them all in one action.

- Tables: `holidays` (id, start date, end date) and `holiday_skips` (holiday id, goal id, date), which records the `skips` rows the holiday added.
- Ending a holiday removes only the rows in `holiday_skips`. A skip made by hand stays.
- Each goal is paused only from its creation day onward. Goals created during a holiday are not included.
- Tasks are not moved and still count in the day score.
- Blaze streak: a holiday day with nothing done neither counts nor breaks the streak. This changes `day_records` in `progress.rs`, and needs tests for the streak and the 7-day XP bonus.
- Monsters and quit habits: first write tests that show what happens today. The monster rules count a clean day when nothing is logged, so a quit habit may still damage a monster during a holiday. Decide from what the tests show.

## Stage 5: day notes (M, migration 021)

Table `day_notes` (date as the key, text up to 2,000 characters, optional mood 1 to 5).

- Written from Today and from the History day detail. Shown in History.
- It never changes score, XP, streaks or rewards.
- Private: never shown on widgets, notifications or the insights share text. Included in backups and JSON export.
- Insights adds average mood for the period, and for Blaze or Titan days against other days. It shows nothing with fewer than 7 notes.

## Stage 6: weekly review (L, migration 022)

Tables `week_reviews` (week start as the key, note, finished date, dismissed date) and `week_focus` (week start, kind, item id, position).

- Offered once when a new week starts, using `meta.week_start`. Dismissing it saves the dismissed date, costs nothing, and it does not ask again that week.
- Three steps: look back (`insights` plus records), clear up (overdue tasks with move, archive or trash, through `bulk_update`), choose up to three focus items.
- Focus items show as a small card on Today. An item that gets trashed or deleted drops off the card.
- It only plans. It gives no XP and changes no score, streak or reward.

## Stage 7: routines (M, migration 023)

An ordered list of goals done together, such as a morning routine. Tables `routines` and `routine_steps` (routine, goal, position, `ON DELETE CASCADE`).

- A routine screen walks through the steps planned for today. A step ticks through the same path as Today, so rewards and streaks work as they do now. Finishing a routine gives no extra XP or reward.
- Yes/no habits tick in one tap. Numeric goals open the counter. Quit habits and tasks cannot be steps.
- Steps that are trashed, skipped, or off today are hidden. A routine with no steps left today is not shown.
- Optional start time on the Calendar and an optional reminder, using the existing `Reminders.kt` code.

## Stage 8: backup password (L)

Save backup and Share backup both write the whole database unencrypted, and both files can leave the phone. Both get an optional password.

- Encrypt in Rust with Argon2id and XChaCha20-Poly1305, using the RustCrypto crates. Do not write custom crypto. Check the APK size and the time on a phone, and tune Argon2 to about one second.
- File layout: a magic header, format version, salt, nonce, Argon2 settings, then the ciphertext. The whole header is authenticated as AEAD associated data, so changing any of it fails decryption.
- Set limits on the Argon2 settings before using them, because a backup is untrusted and huge values could freeze the phone.
- Restore decrypts into the app's cache folder, then runs the existing `validate` and swap. The temporary file is always deleted, including after a failure.
- The message for a failed decryption is "wrong password or damaged file", because the two look the same.
- The app says clearly, before saving, that a lost password cannot be recovered.
- Plain backups still restore. Automatic daily backups stay unencrypted, because they would need a stored password. Settings says so.
- Tests: round trip, wrong password, any changed byte rejected (header included), oversized settings rejected, encrypted file with bad contents still rejected by `validate`.

## Stage 9: accessibility, text, bestiary (M)

1. TalkBack pass on every screen: the charts, the habit grid, calendar blocks and drag to move need a spoken alternative.
2. Move texts containing values to `strings.xml`, using plurals for counts. Translations are still not planned.
3. Large text and landscape pass on the newest screens.
4. Bestiary: a read-only list of past weekly bosses and beaten monsters, from `weekly_bosses` and `monster_rounds`. It lives in the Monsters screens, so the "a monster" name rule holds.

## Stage 10: release (S)

1. Bump `versionCode` to 15 and `versionName` to 0.11.0. Add a `changelog` entry in `Onboarding.kt`.
2. Update the schema line and the new rules in `CLAUDE.md`, and the Features list and device checklist in the README.
3. Run the device checklist with the release APK installed over 0.10.0. R8 has broken things before.

## Not in this release

- Boss modifiers: they would change how points work.
- Calendar file import and export (`.ics`), cloud sync, translations.
- A foreground service: deliberately not used, as decided in 0.8.0.

## If time runs short

Cut in this order: bestiary, quick settings tile, routine calendar slot, mood in insights. Do not cut stage 1, stage 2 or the backup password.

## V0.11.0 as built

Shipped in 0.11.0 (`versionCode` 15, schema 23): stage 1 fixes (backup date checks, insights follow the week start, README), CI on every push and pull request, a speed test with 5,000 tasks and three years of logs, share-to-add, app shortcuts (New task, Today), holiday mode, day notes with mood insights, the weekly review, routines. Also added during the build: editing the due date and priority of several selected tasks at once, and a fix for the Undo message that never appeared (a Compose effect cancelled itself).

Not built, and moved to a later release:

- Password-protected backups (built, then removed before release by the owner's choice).
- Quick settings tile and the "Start focus" shortcut.
- Routine start time on the Calendar and routine reminders.
- The Compose `androidTest` smoke test.
- Splitting `core/src/tests.rs` into one file per module.
- Plurals in `strings.xml`, and the TalkBack pass.
- Bestiary: the Monsters screens already list past bosses and beaten monsters, so nothing new was needed.

## Treat stock (0.11.2, migration 024)

- `treats.once`: a one-time treat leaves the jar once it is won from a target, a weekly boss or a monster.
  Undoing the win deletes its row, so the treat is back in the jar. Repeatable is the default, and the
  switch can be flipped at any time without touching past wins.
- All draws pick from `rewards::jar_treats`. With only won one-time treats left, the jar counts as empty.
- Stock: wins with no `redeemed_date`, grouped by treat name with a count. "Use one" marks the oldest one
  enjoyed (`toggle_redeemed`), with Undo. The full list stays under History.

# V0.12.0: stats and insights, habit grid widget, save-up shop

Migration 025. Nothing here changes streaks, rewards odds or the day score.

- **Log times:** `entries.minute` (0 to 1439) comes from the phone through `toggle_check_in_at` and
  `log_amount_at`, and is kept only when the log is for today. Older logs and filled-in past days have
  none. `time_profile(goal)` buckets them: morning 5-12, afternoon 12-17, evening 17-22, night. The usual
  part shows once there are 5 timed logs; ties go to the earlier part.
- **Goal page:** chart by days, weeks or months over 12, 26 or 52 periods; best and hardest weekday from
  `goal_weekdays` (a daily goal counts met days, others count days with a log; shown once a weekday has
  2 eligible days); the usual time of day (for a quit habit: when slips happen).
- **Year in review** (`year.rs`): logs (no slips), active days, longest run of active days, targets met
  (a period target counts in its own period), tasks done (no milestone tasks), treats won and bought,
  XP, Titan days and the most logged goal. Trashed goals are left out. The share image never includes
  notes or moods.
- **Habit grid widget:** habits for the last 5 days; tap toggles the day like the Habits tab.
- **Shop:** 1 coin per 10 XP (`XP_PER_COIN`). A treat with a `price` is for sale; buying needs enough
  coins, adds a row to `shop_purchases`, and the treat lands in the stock. XP can fall when a day is
  undone, so the balance can dip below zero; nothing bought is taken back, and buying waits until it is
  positive. Unused purchases can be refunded. A one-time treat can be bought once.
