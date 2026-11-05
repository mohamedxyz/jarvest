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
