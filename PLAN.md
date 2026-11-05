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
