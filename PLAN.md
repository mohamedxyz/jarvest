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
